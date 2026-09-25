package com.ledgerflow.outbox.internal;

import com.ledgerflow.outbox.EventCodec;
import java.time.Duration;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.TopicPartition;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.util.backoff.FixedBackOff;
import org.springframework.kafka.support.ExponentialBackOffWithMaxRetries;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(name = "ledgerflow.events.enabled", havingValue = "true")
class KafkaConfiguration {
    @Bean
    ConcurrentKafkaListenerContainerFactory<String, String> deadLetterContainerFactory(ConsumerFactory<String, String> consumers) {
        var factory = new ConcurrentKafkaListenerContainerFactory<String, String>();
        factory.setConsumerFactory(consumers);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.RECORD);
        // The terminal inbox must never discard a record when PostgreSQL is unavailable.
        factory.setCommonErrorHandler(new DefaultErrorHandler(new FixedBackOff(5000, FixedBackOff.UNLIMITED_ATTEMPTS)));
        return factory;
    }
    @Bean
    NewTopic eventsTopic(@Value("${ledgerflow.events.topic}") String topic, @Value("${ledgerflow.events.replicas:1}") int replicas) {
        return TopicBuilder.name(topic).partitions(6).replicas(replicas).config("retention.ms", "604800000").build();
    }
    @Bean
    NewTopic deadLetterTopic(@Value("${ledgerflow.events.topic}") String topic, @Value("${ledgerflow.events.replicas:1}") int replicas) {
        return TopicBuilder.name(topic + ".dlt").partitions(6).replicas(replicas).config("retention.ms", "2592000000").build();
    }
    @Bean
    DefaultErrorHandler kafkaErrorHandler(KafkaTemplate<String, String> template) {
        var recoverer = new DeadLetterPublishingRecoverer(template, (record, exception) -> new TopicPartition(record.topic() + ".dlt", record.partition()));
        recoverer.setFailIfSendResultIsError(true);
        recoverer.setWaitForSendResultTimeout(Duration.ofSeconds(5));
        var backoff = new ExponentialBackOffWithMaxRetries(3);
        backoff.setInitialInterval(250); backoff.setMultiplier(2); backoff.setMaxInterval(2000);
        var handler = new DefaultErrorHandler(recoverer, backoff);
        handler.addNotRetryableExceptions(EventCodec.InvalidEvent.class);
        return handler;
    }
}
