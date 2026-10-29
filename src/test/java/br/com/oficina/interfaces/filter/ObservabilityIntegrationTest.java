package br.com.oficina.interfaces.filter;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.not;

import br.com.oficina.testsupport.TestTokens;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.specification.RequestSpecification;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.jboss.logmanager.ExtHandler;
import org.jboss.logmanager.ExtLogRecord;
import org.jboss.logmanager.Logger;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

/**
 * Correlação e métricas de negócio no pipeline real: o id chega à resposta (inclusive nas negadas
 * pela segurança), chega ao MDC da thread que atende a requisição, e o funil aparece em /q/metrics.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ObservabilityIntegrationTest {

    private static final String UUID_PATTERN = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";

    private static Long clientId;
    private static Long orderId;

    private static RequestSpecification asStaff(String role) {
        return given().auth().oauth2(TestTokens.staff(role)).contentType(ContentType.JSON);
    }

    private static RequestSpecification asClient(Long id) {
        return given().auth().oauth2(TestTokens.client(id)).contentType(ContentType.JSON);
    }

    // ===== Correlação =====

    @Test
    @Order(1)
    void everyResponse_shouldCarryAGeneratedCorrelationId() {
        given().when().get("/q/health/live")
            .then().statusCode(200)
            .header("X-Correlation-Id", matchesPattern(UUID_PATTERN));
    }

    @Test
    @Order(2)
    void incomingCorrelationId_shouldBeEchoedBack() {
        given().header("X-Correlation-Id", "req-do-front-42").when().get("/q/health/live")
            .then().header("X-Correlation-Id", equalTo("req-do-front-42"));
    }

    @Test
    @Order(3)
    void gatewayRequestId_shouldBeUsedWhenThereIsNoCorrelationId() {
        given().header("X-Amzn-Request-Id", "gw-req-789").when().get("/q/health/live")
            .then().header("X-Correlation-Id", equalTo("gw-req-789"));
    }

    @Test
    @Order(4)
    void unsafeCorrelationId_shouldBeReplaced() {
        given().header("X-Correlation-Id", "com espaco <e> simbolos").when().get("/q/health/live")
            .then().header("X-Correlation-Id", matchesPattern(UUID_PATTERN));
    }

    /** O 401 nasce na camada de segurança, antes do JAX-RS: é o caso que um filtro JAX-RS perderia. */
    @Test
    @Order(5)
    void securityDeniedResponse_shouldAlsoCarryTheCorrelationId() {
        given().header("X-Correlation-Id", "negada-777").when().get("/admin/clients")
            .then().statusCode(401)
            .header("X-Correlation-Id", equalTo("negada-777"));
    }

    @Test
    @Order(6)
    void correlationId_shouldReachTheMdcOfTheThreadThatHandlesTheRequest() {
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
            given().contentType(ContentType.JSON).header("X-Correlation-Id", "mdc-check-001")
                .body("{\"username\": \"nao-existe\", \"password\": \"senha-errada-123\"}")
                .when().post("/auth/login")
                .then().statusCode(401);
        } finally {
            logger.removeHandler(handler);
        }

        assertThat(captured)
            .as("o log da tentativa de login inválida deve carregar o correlationId no MDC")
            .anySatisfy(record -> assertThat(record.getMdc("correlationId")).isEqualTo("mdc-check-001"));
    }

    /** Spec 05 §5: nenhuma senha (nem em mensagem, nem em MDC) pode chegar ao log, de nenhuma categoria. */
    @Test
    @Order(7)
    void failedLogin_shouldNeverLeakThePasswordToAnyLog() {
        List<ExtLogRecord> captured = new CopyOnWriteArrayList<>();
        ExtHandler handler = new ExtHandler() {
            @Override
            protected void doPublish(ExtLogRecord record) {
                captured.add(record);
            }
        };
        Logger root = Logger.getLogger("");
        root.addHandler(handler);
        try {
            given().contentType(ContentType.JSON).header("X-Correlation-Id", "leak-check-001")
                .body("{\"username\": \"nao-existe\", \"password\": \"senha-vazada-987\"}")
                .when().post("/auth/login")
                .then().statusCode(401);
        } finally {
            root.removeHandler(handler);
        }

        assertThat(captured).isNotEmpty();
        assertThat(captured).noneSatisfy(record -> {
            assertThat(String.valueOf(record.getFormattedMessage())).contains("senha-vazada-987");
            assertThat(String.valueOf(record.getMdcCopy())).contains("senha-vazada-987");
            assertThat(String.valueOf(record.getThrown())).contains("senha-vazada-987");
        });
    }

    // ===== Métricas de negócio =====

    @Test
    @Order(10)
    void setup_createClientVehicleAndOpenAnOrderAsTheClient() {
        clientId = asStaff("ADMIN")
            .body("{\"name\": \"Cliente Metricas\", \"cpfCnpj\": \"453.178.287-91\", \"clientType\": \"PF\"}")
            .when().post("/admin/clients")
            .then().statusCode(201).extract().<Integer>path("id").longValue();
        Long vehicleId = asStaff("ADMIN")
            .body("{\"licensePlate\": \"MET1M11\", \"brand\": \"Fiat\", \"model\": \"Uno\", \"productionYear\": 2015, \"clientId\": %d}"
                .formatted(clientId))
            .when().post("/admin/vehicles")
            .then().statusCode(201).extract().<Integer>path("id").longValue();
        orderId = asClient(clientId)
            .body("{\"vehicleId\": %d}".formatted(vehicleId))
            .when().post("/me/work-orders")
            .then().statusCode(201).extract().<Integer>path("id").longValue();
    }

    @Test
    @Order(11)
    void transitionsAndErrors_shouldAppearInMetrics() {
        asStaff("ADMIN").when().patch("/admin/work-orders/" + orderId + "/start-diagnosis").then().statusCode(200);
        // Repetir a transição é inválido (a OS já saiu de RECEIVED): conta como erro, não como funil.
        asStaff("ADMIN").when().patch("/admin/work-orders/" + orderId + "/start-diagnosis").then().statusCode(422);
        // CLIENT em rota de staff: 403 gerado pela segurança.
        asClient(clientId).when().get("/admin/clients").then().statusCode(403);

        String metrics = given().when().get("/q/metrics").then().statusCode(200).extract().asString();

        assertThat(metrics)
            .containsPattern("oficina_work_orders_created_total\\{[^}]*origin=\"client\"[^}]*\\} [1-9]")
            .containsPattern("oficina_work_order_status_transitions_total\\{[^}]*from=\"RECEIVED\"[^}]*to=\"IN_DIAGNOSIS\"[^}]*\\} [1-9]")
            .containsPattern("oficina_work_order_status_duration_seconds_count\\{[^}]*status=\"RECEIVED\"[^}]*\\} [1-9]")
            .containsPattern("oficina_work_order_transition_errors_total\\{[^}]*reason=\"invalid_transition\"[^}]*\\} [1-9]")
            .containsPattern("oficina_auth_denied_total\\{[^}]*reason=\"forbidden\"[^}]*\\} [1-9]")
            .containsPattern("oficina_auth_denied_total\\{[^}]*reason=\"unauthenticated\"[^}]*\\} [1-9]");
    }

    @Test
    @Order(12)
    void metricsEndpoint_shouldNotExposeIdentifiersAsTags() {
        String metrics = given().when().get("/q/metrics").then().statusCode(200)
            .body(not(org.hamcrest.Matchers.containsString("453.178.287-91")))
            .extract().asString();

        assertThat(metrics).doesNotContainPattern("oficina_[a-z_]+\\{[^}]*(clientId|cpf|orderNumber)=");
    }
}
