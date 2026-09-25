package com.ledgerflow.shared;

import io.micrometer.core.instrument.MeterRegistry;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.context.Context;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Component
public class Telemetry {
    private final OpenTelemetry otel;
    private final MeterRegistry meters;
    public Telemetry(OpenTelemetry otel, MeterRegistry meters) { this.otel = otel; this.meters = meters; }
    public String capture() {
        return currentTraceParent();
    }
    public static String currentTraceParent() {
        var context = Span.current().getSpanContext();
        return context.isValid() ? "00-" + context.getTraceId() + "-" + context.getSpanId() + "-" + context.getTraceFlags().asHex() : null;
    }
    public <T> T observe(String name, String parent, Supplier<T> action) {
        Context context = Context.current();
        if (parent != null && parent.matches("00-[0-9a-f]{32}-[0-9a-f]{16}-[0-9a-f]{2}")) {
            var remote = SpanContext.createFromRemoteParent(parent.substring(3, 35), parent.substring(36, 52), TraceFlags.fromHex(parent, 53), TraceState.getDefault());
            if (remote.isValid()) context = Context.root().with(Span.wrap(remote));
        }
        var span = otel.getTracer("com.ledgerflow").spanBuilder(name).setParent(context).startSpan();
        var scope = span.makeCurrent(); long started = System.nanoTime();
        try { return action.get(); }
        catch (RuntimeException failure) { span.setStatus(StatusCode.ERROR, "operation-failed"); throw failure; }
        finally { scope.close(); span.end(); meters.timer(name).record(System.nanoTime() - started, TimeUnit.NANOSECONDS); }
    }
    public void committedEvent(String type) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) throw new IllegalStateException("Event metric requires a transaction");
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() { meters.counter("ledgerflow.events.committed", "type", type).increment(); }
        });
    }
    public void webhookResult(String result, long durationMs) {
        meters.counter("ledgerflow.webhook.attempts", "result", result).increment();
        meters.timer("ledgerflow.webhook.http", "result", result).record(durationMs, TimeUnit.MILLISECONDS);
    }
}
