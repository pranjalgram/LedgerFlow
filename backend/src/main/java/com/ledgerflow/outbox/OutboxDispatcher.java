package com.ledgerflow.outbox;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "ledgerflow.events.enabled", havingValue = "true")
public class OutboxDispatcher {
    private final OutboxQueue queue;
    private final KafkaTemplate<String, String> kafka;
    private final EventCodec codec;
    private final String topic;

    public OutboxDispatcher(OutboxQueue queue, KafkaTemplate<String, String> kafka, EventCodec codec,
            @Value("${ledgerflow.events.topic}") String topic) { this.queue = queue; this.kafka = kafka; this.codec = codec; this.topic = topic; }

    @Scheduled(fixedDelayString = "${ledgerflow.events.poll-delay:1000}", initialDelayString = "${ledgerflow.events.initial-delay:5000}")
    public void dispatchOnce() {
        for (var event : queue.claim()) {
            try {
                codec.decode(event.payload());
                kafka.send(topic, event.messageKey(), event.payload()).get(5, TimeUnit.SECONDS);
                queue.published(event);
            } catch (EventCodec.InvalidEvent invalid) {
                queue.block(event);
            } catch (ExecutionException | TimeoutException | org.apache.kafka.common.KafkaException | org.springframework.kafka.KafkaException unavailable) {
                queue.retry(event, "broker-unavailable");
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                queue.retry(event, "publisher-interrupted");
                return;
            }
        }
    }
}
