package br.com.oficina.interfaces.rest;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import br.com.oficina.testsupport.TestTokens;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.specification.RequestSpecification;
import java.util.stream.Stream;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.ArgumentsProvider;
import org.junit.jupiter.params.provider.ArgumentsSource;

/**
 * Matriz rota × papel. É o teste que pega regressão de anotação — o modo mais comum de abrir uma
 * rota por acidente. Para rota permitida, só se exige "não ser 401/403" (o corpo vazio de propósito
 * pode resultar em 400/404); para rota negada, o status exato: 401 sem token, 403 com papel errado.
 */
@QuarkusTest
class RbacMatrixTest {

    private static final String ANON = "ANON";
    private static final String CLIENT = "CLIENT";
    private static final String MECHANIC = "MECHANIC";
    private static final String ADMIN = "ADMIN";

    @ParameterizedTest(name = "{0} {1} como {2}")
    @ArgumentsSource(Matrix.class)
    void route_shouldEnforceTheExpectedRoles(String method, String path, String role, boolean allowed) {
        int status = request(role).when().request(method, path).getStatusCode();

        if (allowed) {
            assertThat(status).as("%s %s como %s deveria ser permitido", method, path, role)
                .isNotIn(401, 403);
        } else {
            int expected = ANON.equals(role) ? 401 : 403;
            assertThat(status).as("%s %s como %s deveria ser negado", method, path, role)
                .isEqualTo(expected);
        }
    }

    private static RequestSpecification request(String role) {
        RequestSpecification spec = given().contentType(ContentType.JSON).body("{}");
        return switch (role) {
            case ANON -> spec;
            case CLIENT -> spec.auth().oauth2(TestTokens.client(999_999L));
            default -> spec.auth().oauth2(TestTokens.staff(role));
        };
    }

    static class Matrix implements ArgumentsProvider {

        @Override
        public Stream<? extends Arguments> provideArguments(ExtensionContext context) {
            return Stream.of(
                // staff (ADMIN + MECHANIC)
                route("GET", "/admin/clients", MECHANIC, ADMIN),
                route("GET", "/admin/vehicles", MECHANIC, ADMIN),
                route("GET", "/admin/parts", MECHANIC, ADMIN),
                route("GET", "/admin/services", MECHANIC, ADMIN),
                route("GET", "/admin/work-orders", MECHANIC, ADMIN),
                route("PATCH", "/admin/work-orders/999999/start-diagnosis", MECHANIC, ADMIN),
                // somente ADMIN
                route("POST", "/admin/clients", ADMIN),
                route("DELETE", "/admin/clients/999999", ADMIN),
                route("DELETE", "/admin/parts/999999", ADMIN),
                route("PATCH", "/admin/work-orders/999999/cancel", ADMIN),
                route("GET", "/admin/metrics", ADMIN),
                // somente CLIENT
                route("GET", "/me", CLIENT),
                route("GET", "/me/vehicles", CLIENT),
                route("GET", "/me/work-orders", CLIENT),
                route("GET", "/me/work-orders/999999", CLIENT),
                route("POST", "/me/work-orders", CLIENT),
                route("POST", "/me/work-orders/999999/approval", CLIENT),
                // públicas
                route("GET", "/public/work-orders/OS-999999/status", ANON, CLIENT, MECHANIC, ADMIN),
                route("POST", "/auth/login", ANON, CLIENT, MECHANIC, ADMIN),
                route("GET", "/q/health/live", ANON, CLIENT, MECHANIC, ADMIN)
            ).flatMap(Matrix::expand);
        }

        private static Route route(String method, String path, String... allowedRoles) {
            return new Route(method, path, java.util.Set.of(allowedRoles));
        }

        private static Stream<Arguments> expand(Route route) {
            return Stream.of(ANON, CLIENT, MECHANIC, ADMIN)
                .map(role -> Arguments.of(route.method(), route.path(), role,
                    route.allowedRoles().contains(role)));
        }

        private record Route(String method, String path, java.util.Set<String> allowedRoles) {
        }
    }
}
