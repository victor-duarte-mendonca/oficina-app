package br.com.oficina.interfaces.filter;

import static io.restassured.RestAssured.given;

import br.com.oficina.testsupport.TestTokens;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** O filtro no pipeline real, com a chave do gateway configurada (perfil que a liga). */
@QuarkusTest
@TestProfile(GatewayKeyIntegrationTest.WithGatewayKey.class)
class GatewayKeyIntegrationTest {

    private static final String KEY = "chave-de-teste-do-gateway";

    public static class WithGatewayKey implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("app.gateway.key", KEY);
        }
    }

    @Test
    void admin_withoutGatewayKey_shouldBeForbiddenEvenWithAValidToken() {
        given().auth().oauth2(TestTokens.staff("ADMIN"))
            .when().get("/admin/clients")
            .then().statusCode(403);
    }

    @Test
    void admin_withWrongGatewayKey_shouldBeForbidden() {
        given().auth().oauth2(TestTokens.staff("ADMIN")).header("X-Gateway-Key", "errada")
            .when().get("/admin/clients")
            .then().statusCode(403);
    }

    @Test
    void admin_withCorrectGatewayKey_shouldPass() {
        given().auth().oauth2(TestTokens.staff("ADMIN")).header("X-Gateway-Key", KEY)
            .when().get("/admin/clients")
            .then().statusCode(200);
    }

    @Test
    void me_withoutGatewayKey_shouldBeForbidden() {
        given().auth().oauth2(TestTokens.client(1L))
            .when().get("/me")
            .then().statusCode(403);
    }

    @Test
    void health_withoutGatewayKey_shouldPass() {
        given().when().get("/q/health/live").then().statusCode(200);
    }

    @Test
    void publicTracking_withoutGatewayKey_shouldNotBeBlockedByTheFilter() {
        // 404 (OS inexistente) prova que passou pelo filtro e chegou ao recurso.
        given().when().get("/public/work-orders/OS-999999/status").then().statusCode(404);
    }
}
