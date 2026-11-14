package br.com.fiap.postech.config;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;

import io.micrometer.observation.ObservationRegistry;
import io.micrometer.tracing.Tracer;
import org.apache.kafka.clients.producer.MockProducer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(OutputCaptureExtension.class)
@SpringBootTest(classes = ObservabilityIntegrationTest.TestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "security.jwt.secret=observability-test-secret-at-least-32-characters",
        "management.endpoint.health.group.readiness.include=readinessState",
        "management.tracing.sampling.probability=1.0",
        "management.tracing.export.otlp.enabled=false"
})
class ObservabilityIntegrationTest {
    @Value("${local.server.port}")
    private int port;

    private final HttpClient client = HttpClient.newHttpClient();

    @Autowired
    private ObservationRegistry observationRegistry;

    @Autowired
    private Tracer tracer;

    @Test
    @SuppressWarnings("unchecked")
    void injectsW3cContextIntoKafkaHeadersWithoutBroker() throws Exception {
        MockProducer<String, String> producer = new MockProducer<>(true, null, new StringSerializer(), new StringSerializer());
        ProducerFactory<String, String> factory = mock(ProducerFactory.class);
        when(factory.createProducer()).thenReturn(producer);
        KafkaTemplate<String, String> template = new KafkaTemplate<>(factory);
        template.setObservationEnabled(true);
        template.setObservationRegistry(observationRegistry);
        var parent = tracer.nextSpan().name("test-budget-flow").start();
        try (var scope = tracer.withSpan(parent)) {
            template.send("budget-approval-request", "42", "test-event").get();
            var header = producer.history().getFirst().headers().lastHeader("traceparent");
            assertThat(header).isNotNull();
            assertThat(new String(header.value(), StandardCharsets.UTF_8))
                    .startsWith("00-" + parent.context().traceId() + "-");
        } finally {
            parent.end();
            template.destroy();
        }
    }

    @Test
    void propagatesW3cTraceAndWritesSafeCorrelatedLog(CapturedOutput output) throws Exception {
        String traceId = "0123456789abcdef0123456789abcdef";
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/?token=secret-query"))
                .header("traceparent", "00-" + traceId + "-0123456789abcdef-01").build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo(traceId);
        assertThat(output.getOut()).contains("HTTP request completed", traceId, "duration_ms")
                .doesNotContain("secret-query");
    }

    @Test
    void exposesPrometheusHistogramsAndHealthButProtectsOtherEndpoints() throws Exception {
        get("/");
        HttpResponse<String> metrics = get("/actuator/prometheus");
        assertThat(metrics.statusCode()).isEqualTo(200);
        assertThat(metrics.body()).contains("http_server_requests_seconds_bucket", "jvm_memory_used_bytes",
                "application=\"mechanic-workshop-system\"", "environment=\"local\"");
        HttpResponse<String> health = get("/actuator/health");
        assertThat(health.statusCode()).isEqualTo(200);
        assertThat(health.body()).contains("UP").doesNotContain("components", "details");
        assertThat(get("/actuator/health/liveness").statusCode()).isEqualTo(200);
        assertThat(get("/actuator/health/readiness").statusCode()).isEqualTo(200);
        assertThat(get("/actuator/env").statusCode()).isEqualTo(401);
        assertThat(get("/owners").statusCode()).isEqualTo(401);
    }

    private HttpResponse<String> get(String path) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration(exclude = DataSourceAutoConfiguration.class)
    @Import({SecurityConfig.class, JwtAuthenticationFilter.class, RoleAuthorizationFilter.class,
            HttpRequestLoggingFilter.class, TraceController.class})
    static class TestApplication {
    }

    @RestController
    static class TraceController {
        @Autowired
        private Tracer tracer;

        @GetMapping("/")
        String trace() {
            return tracer.currentSpan().context().traceId();
        }
    }
}
