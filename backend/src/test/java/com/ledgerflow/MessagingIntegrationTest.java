package com.ledgerflow;

import static org.assertj.core.api.Assertions.assertThat;

import com.ledgerflow.notification.NotificationService;
import com.ledgerflow.outbox.EventCodec;
import com.ledgerflow.outbox.Outbox;
import com.ledgerflow.outbox.OutboxQueue;
import com.ledgerflow.outbox.OutboxDispatcher;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.kafka.KafkaContainer;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "ledgerflow.events.enabled=true", "ledgerflow.events.initial-delay=3600000", "ledgerflow.events.poll-delay=3600000"
})
class MessagingIntegrationTest extends IntegrationSupport {
    static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:4.3.1");
    static { KAFKA.start(); }
    @DynamicPropertySource
    static void kafkaProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
    }
    @Autowired Outbox outbox;
    @Autowired OutboxQueue queue;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate transactions;
    @Autowired KafkaTemplate<String, String> kafka;
    @Autowired NotificationService notifications;
    @Autowired EventCodec codec;
    @Autowired OutboxDispatcher dispatcher;

    @Test
    void brokerOutageRetainsCommittedEventForRecovery() throws Exception {
        var merchant = register().merchantId();
        UUID id = transactions.execute(status -> outbox.append(merchant, "payment.created", "payment", UUID.randomUUID(), 1, Map.of()));
        var docker = org.testcontainers.DockerClientFactory.instance().client();
        docker.pauseContainerCmd(KAFKA.getContainerId()).exec();
        try {
            dispatcher.dispatchOnce();
            assertThat(jdbc.queryForObject("select state from outbox_event where id=?", String.class, id)).isEqualTo("PENDING");
            assertThat(jdbc.queryForObject("select published_at is null from outbox_event where id=?", Boolean.class, id)).isTrue();
        } finally {
            docker.unpauseContainerCmd(KAFKA.getContainerId()).exec();
        }
        long deadline = System.nanoTime() + Duration.ofSeconds(45).toNanos();
        while (count(id) == 0 && System.nanoTime() < deadline) {
            jdbc.update("update outbox_event set next_attempt_at=now() where id=?", id);
            dispatcher.dispatchOnce();
            Thread.sleep(200);
        }
        assertThat(count(id)).isEqualTo(1);
    }

    @Test
    void dispatcherPublishesAggregateVersionsInOrder() throws Exception {
        var merchant = register().merchantId();
        UUID aggregate = UUID.randomUUID();
        UUID first = transactions.execute(status -> outbox.append(merchant, "payment.created", "payment", aggregate, 1, Map.of()));
        UUID second = transactions.execute(status -> outbox.append(merchant, "payment.succeeded", "payment", aggregate, 2, Map.of()));
        for (int attempt = 0; attempt < 100 && count(first) == 0; attempt++) dispatcher.dispatchOnce();
        await(() -> count(first) == 1);
        dispatcher.dispatchOnce();
        await(() -> count(second) == 1);
        assertThat(jdbc.queryForObject("select published_at is not null from outbox_event where id=?", Boolean.class, second)).isTrue();
        assertThat(jdbc.queryForObject("select a.published_at<=b.published_at from outbox_event a join outbox_event b on b.id=? where a.id=?", Boolean.class, second, first)).isTrue();
    }

    @Test
    void publishCrashReclaimsLeaseAndDuplicateDeliveryHasOneEffect() throws Exception {
        var merchant = register().merchantId();
        UUID id = transactions.execute(status -> outbox.append(merchant, "payment.succeeded", "payment", UUID.randomUUID(), 1, Map.of("amount", 100)));
        var first = claim(id);
        kafka.send("ledgerflow.events.v1", first.messageKey(), first.payload()).get(10, TimeUnit.SECONDS);
        await(() -> count(id) == 1);
        jdbc.update("update outbox_event set lease_until=now()-interval '1 second' where id=?", id);
        var second = claim(id);
        assertThat(second.attempts()).isEqualTo(2);
        assertThat(queue.published(first)).isFalse();
        kafka.send("ledgerflow.events.v1", second.messageKey(), second.payload()).get(10, TimeUnit.SECONDS);
        assertThat(queue.published(second)).isTrue();
        notifications.consume(codec.decode(second.payload()));
        assertThat(count(id)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from processed_event where event_id=?", Integer.class, id)).isEqualTo(1);
    }

    @Test
    void consumerRollbackRollsBackItsDeduplicationMarker() throws Exception {
        var merchant = register().merchantId();
        UUID id = transactions.execute(status -> outbox.append(merchant, "transfer.completed", "transfer", UUID.randomUUID(), 1, Map.of()));
        var event = codec.decode(claim(id).payload());
        transactions.executeWithoutResult(status -> { notifications.consume(event); status.setRollbackOnly(); });
        assertThat(count(id)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from processed_event where event_id=?", Integer.class, id)).isZero();
        notifications.consume(event);
        notifications.consume(event);
        assertThat(count(id)).isEqualTo(1);
    }

    @Test
    void malformedEnvelopeReachesPersistentDeadLetterInbox() throws Exception {
        String poison = "invalid-" + UUID.randomUUID();
        kafka.send("ledgerflow.events.v1", "poison", poison).get(10, TimeUnit.SECONDS);
        await(() -> jdbc.queryForObject("select count(*) from dead_letter where payload=?", Integer.class, poison) == 1);
    }

    private OutboxQueue.Claimed claim(UUID id) {
        for (int attempt = 0; attempt < 100; attempt++) {
            for (var event : queue.claim()) if (event.id().equals(id)) return event;
        }
        throw new AssertionError("Expected eligible outbox event " + id);
    }
    private int count(UUID id) { return jdbc.queryForObject("select count(*) from notification where event_id=?", Integer.class, id); }
    private static void await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(100);
        assertThat(condition.getAsBoolean()).isTrue();
    }
}
