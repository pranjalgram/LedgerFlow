package com.ledgerflow.webhook.internal;

import com.ledgerflow.outbox.EventCodec;
import com.ledgerflow.webhook.Webhooks;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "ledgerflow.events.enabled", havingValue = "true")
class WebhookListener {
    private final Webhooks webhooks;
    private final EventCodec codec;
    WebhookListener(Webhooks webhooks, EventCodec codec) { this.webhooks = webhooks; this.codec = codec; }
    @KafkaListener(topics = "${ledgerflow.events.topic}", groupId = Webhooks.CONSUMER)
    public void receive(String payload) { webhooks.consume(codec.decode(payload)); }
}
