package com.example.demo;

import io.opentelemetry.sdk.autoconfigure.spi.AutoConfigurationCustomizerProvider;
import io.otel.pyroscope.PyroscopeOtelAutoConfigurationCustomizerProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Trace ↔ profile correlation via grafana/otel-profiling-java.
 *
 * <p>The OpenTelemetry Spring Boot starter builds the SDK from the
 * {@link AutoConfigurationCustomizerProvider} beans it finds. We hand it the
 * provider shipped by {@code io.pyroscope:otel}, which adds a span processor
 * that stamps every profile the Pyroscope agent takes with the current
 * {@code trace_id} / {@code span_id}. Those labels are what let Grafana link a
 * Tempo span to its Pyroscope flame graph.
 *
 * <p>Behaviour is tuned with {@code otel.pyroscope.*} properties (see
 * docker-compose.yml). In particular {@code otel.pyroscope.start.profiling} is
 * left off because the Pyroscope javaagent already runs the profiler.
 */
@Configuration
public class SpanProfilesConfig {

    @Bean
    public AutoConfigurationCustomizerProvider pyroscopeOtelSpanProfiles() {
        return new PyroscopeOtelAutoConfigurationCustomizerProvider();
    }
}
