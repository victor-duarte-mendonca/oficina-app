package br.com.oficina.interfaces.filter;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.jboss.logmanager.ExtHandler;
import org.jboss.logmanager.ExtLogRecord;
import org.jboss.logmanager.Logger;
import org.junit.jupiter.api.Test;

/**
 * Traces ligados (SDK ativo, sem exportador): o log da requisição deve carregar {@code traceId} e
 * {@code spanId} no MDC junto do {@code correlationId}, que é o que liga log, trace e gateway no New Relic.
 */
@QuarkusTest
@TestProfile(TracingMdcIntegrationTest.TracingEnabled.class)
class TracingMdcIntegrationTest {

    public static class TracingEnabled implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                "quarkus.otel.sdk.disabled", "false",
                "quarkus.otel.traces.exporter", "none");
        }
    }

    @Test
    void requestLog_shouldCarryTraceAndSpanIdNextToTheCorrelationId() {
        List<ExtLogRecord> captured = new CopyOnWriteArrayList<>();
        ExtHandler handler = new ExtHandler() {
            @Override
            protected void doPublish(ExtLogRecord record) {
                captured.add(record);
            }
        };
        Logger logger = Logger.getLogger("br.com.oficina.infrastructure.security.AuthService");
        logger.addHandler(handler);
        try {
            given().contentType(ContentType.JSON).header("X-Correlation-Id", "trace-check-001")
                .body("{\"username\": \"nao-existe\", \"password\": \"senha-errada-123\"}")
                .when().post("/auth/login")
                .then().statusCode(401);
        } finally {
            logger.removeHandler(handler);
        }

        assertThat(captured).anySatisfy(record -> {
            assertThat(record.getMdc("correlationId")).isEqualTo("trace-check-001");
            assertThat(record.getMdc("traceId")).matches("[0-9a-f]{32}");
            assertThat(record.getMdc("spanId")).matches("[0-9a-f]{16}");
        });
    }
}
