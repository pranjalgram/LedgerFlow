package com.ledgerflow.webhook;

import com.ledgerflow.shared.DomainException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "ledgerflow.events.enabled", havingValue = "true")
public class WebhookDispatcher {
    private final WebhookQueue queue;
    private final WebhookTransport transport;
    private final WebhookSecrets secrets;
    private final com.ledgerflow.shared.Telemetry telemetry;
    public WebhookDispatcher(WebhookQueue queue, WebhookTransport transport, WebhookSecrets secrets, com.ledgerflow.shared.Telemetry telemetry) {
        this.queue = queue; this.transport = transport; this.secrets = secrets; this.telemetry = telemetry;
    }
    @Scheduled(fixedDelayString = "${ledgerflow.webhooks.poll-delay:1000}", initialDelayString = "${ledgerflow.webhooks.initial-delay:5000}")
    public void dispatchOnce() {
        for (var job : queue.claim()) {
            telemetry.observe("ledgerflow.webhook.delivery", job.traceContext(), () -> { deliver(job); return null; });
        }
    }
    private void deliver(WebhookQueue.Job job) {
            WebhookTransport.Result result;
            try {
                result = job.enabled() ? transport.post(job.url(), job.eventId(), job.payload(), secrets.decrypt(job.endpointId(), job.encryptedSecret()))
                        : new WebhookTransport.Result(null, "endpoint-disabled", 0);
            } catch (DomainException | IllegalStateException unavailable) {
                result = new WebhookTransport.Result(null, "secret-unavailable", 0);
            }
            if (queue.finish(job, result)) telemetry.webhookResult(result.succeeded() ? "success" : "failure", result.durationMs());
    }
}
