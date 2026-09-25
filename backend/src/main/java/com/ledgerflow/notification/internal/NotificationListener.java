package com.ledgerflow.notification.internal;

import com.ledgerflow.notification.NotificationService;
import com.ledgerflow.outbox.EventCodec;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "ledgerflow.events.enabled", havingValue = "true")
class NotificationListener {
    private final EventCodec codec;
    private final NotificationService notifications;
    NotificationListener(EventCodec codec, NotificationService notifications) { this.codec = codec; this.notifications = notifications; }

    @KafkaListener(topics = "${ledgerflow.events.topic}", groupId = NotificationService.CONSUMER)
    void receive(String payload) { notifications.consume(codec.decode(payload)); }
}
