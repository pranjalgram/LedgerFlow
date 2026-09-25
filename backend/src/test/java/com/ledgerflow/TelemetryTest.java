package com.ledgerflow;

import static org.assertj.core.api.Assertions.assertThat;
import com.ledgerflow.shared.Telemetry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import java.util.Collection;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;

class TelemetryTest {
    @Test
    void persistedContextContinuesTheSameTraceAfterOriginalScopeCloses() {
        var spans = new CopyOnWriteArrayList<SpanData>();
        var exporter = new SpanExporter() {
            @Override public CompletableResultCode export(Collection<SpanData> values) { spans.addAll(values); return CompletableResultCode.ofSuccess(); }
            @Override public CompletableResultCode flush() { return CompletableResultCode.ofSuccess(); }
            @Override public CompletableResultCode shutdown() { return CompletableResultCode.ofSuccess(); }
        };
        var meters = new SimpleMeterRegistry();
        var provider = SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(exporter)).build();
        try (var sdk = OpenTelemetrySdk.builder().setTracerProvider(provider).build()) {
            var telemetry = new Telemetry(sdk, meters);
            String parent = telemetry.observe("test.original", null, telemetry::capture);
            assertThat(parent).matches("00-[0-9a-f]{32}-[0-9a-f]{16}-[0-9a-f]{2}");
            assertThat(Integer.parseInt(parent.substring(53), 16) & 1).isEqualTo(1);
            assertThat(telemetry.capture()).isNull();
            telemetry.observe("test.recovered", parent, () -> "done");
            assertThat(spans).hasSize(2);
            assertThat(spans.get(1).getTraceId()).isEqualTo(spans.get(0).getTraceId());
            assertThat(spans.get(1).getParentSpanId()).isEqualTo(spans.get(0).getSpanId());
            assertThat(meters.timer("test.recovered").count()).isEqualTo(1);
        } finally { meters.close(); }
    }
}
