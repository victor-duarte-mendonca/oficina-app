package br.com.oficina.interfaces.rest;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;

import br.com.oficina.testsupport.TestTokens;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

/**
 * Área do cliente com tokens reais: o cliente A só enxerga o que é dele, e o que é do cliente B
 * responde 404 — nunca 403, para não confirmar que o id existe.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class MeResourceTest {

    private static Long clientA;
    private static Long clientB;
    private static Long vehicleA;
    private static Long vehicleB;
    private static Long orderA;

    private static RequestSpecification asStaff(String role) {
        return given().auth().oauth2(TestTokens.staff(role)).contentType(ContentType.JSON);
    }

    private static RequestSpecification asClient(Long clientId) {
        return given().auth().oauth2(TestTokens.client(clientId)).contentType(ContentType.JSON);
    }

    private static Long createClient(String name, String cpf, String email) {
        return asStaff("ADMIN")
            .body("""
                {"name": "%s", "cpfCnpj": "%s", "clientType": "PF", "email": "%s"}
                """.formatted(name, cpf, email))
            .when().post("/admin/clients")
            .then().statusCode(201)
            .extract().<Integer>path("id").longValue();
    }

    private static Long createVehicle(String plate, Long ownerId) {
        return asStaff("ADMIN")
            .body("""
                {"licensePlate": "%s", "brand": "Fiat", "model": "Uno", "productionYear": 2015, "clientId": %d}
                """.formatted(plate, ownerId))
            .when().post("/admin/vehicles")
            .then().statusCode(201)
            .extract().<Integer>path("id").longValue();
    }

    @Test
    @Order(1)
    void setup_createTwoClientsEachWithAVehicle() {
        clientA = createClient("Cliente A Me", "123.456.789-09", "a.me@test.com");
        clientB = createClient("Cliente B Me", "987.654.321-00", "b.me@test.com");
        vehicleA = createVehicle("MEA1A11", clientA);
        vehicleB = createVehicle("MEB2B22", clientB);
    }

    @Test
    @Order(2)
    void profile_shouldReturnTheTokenOwnersCadastre() {
        asClient(clientA)
            .when().get("/me")
            .then().statusCode(200)
            .body("id", equalTo(clientA.intValue()))
            .body("name", equalTo("Cliente A Me"));
    }

    @Test
    @Order(3)
    void vehicles_shouldListOnlyTheTokenOwnersVehicles() {
        asClient(clientA)
            .when().get("/me/vehicles")
            .then().statusCode(200)
            .body("", hasSize(1))
            .body("[0].id", equalTo(vehicleA.intValue()));
    }

    @Test
    @Order(4)
    void openWorkOrder_withOwnVehicle_shouldCreateAndCountAsClientOrigin() {
        orderA = asClient(clientA)
            .body("{\"vehicleId\": %d, \"notes\": \"barulho na suspensão\"}".formatted(vehicleA))
            .when().post("/me/work-orders")
            .then().statusCode(201)
            .body("orderNumber", notNullValue())
            .body("status", equalTo("RECEIVED"))
            .extract().<Integer>path("id").longValue();
    }

    @Test
    @Order(5)
    void openWorkOrder_withAnotherClientsVehicle_shouldBeNotFound() {
        asClient(clientA)
            .body("{\"vehicleId\": %d}".formatted(vehicleB))
            .when().post("/me/work-orders")
            .then().statusCode(404);
    }

    @Test
    @Order(6)
    void openWorkOrder_shouldIgnoreAnyClientIdentityInTheBody() {
        // O id do cliente vem do token; campos extras no corpo não mudam de quem é a OS.
        asClient(clientA)
            .body("{\"vehicleId\": %d, \"clientId\": %d, \"clientCpfCnpj\": \"98765432100\"}"
                .formatted(vehicleA, clientB))
            .when().post("/me/work-orders")
            .then().statusCode(201)
            .body("clientCpfCnpj", equalTo("12345678909"));
    }

    @Test
    @Order(7)
    void listWorkOrders_shouldReturnOnlyTheTokenOwnersOrders() {
        asClient(clientA)
            .when().get("/me/work-orders")
            .then().statusCode(200)
            .header("X-Total-Count", equalTo("2"))
            .body("clientCpfCnpj", everyItem(equalTo("12345678909")));

        asClient(clientB)
            .when().get("/me/work-orders")
            .then().statusCode(200)
            .header("X-Total-Count", equalTo("0"))
            .body("", hasSize(0));
    }

    @Test
    @Order(8)
    void findWorkOrder_ofTheOwner_shouldReturn200() {
        asClient(clientA)
            .when().get("/me/work-orders/" + orderA)
            .then().statusCode(200)
            .body("id", equalTo(orderA.intValue()));
    }

    @Test
    @Order(9)
    void findWorkOrder_ofAnotherClient_shouldBe404NotForbidden() {
        asClient(clientB)
            .when().get("/me/work-orders/" + orderA)
            .then().statusCode(404);
    }

    @Test
    @Order(10)
    void approval_outsideAwaitingApproval_shouldBeRejectedByTheStateMachine() {
        asClient(clientA)
            .body("{\"decision\": \"APPROVE\"}")
            .when().post("/me/work-orders/" + orderA + "/approval")
            .then().statusCode(not(equalTo(200)));
    }

    @Test
    @Order(11)
    void approval_onAnotherClientsOrder_shouldBe404() {
        asClient(clientB)
            .body("{\"decision\": \"APPROVE\"}")
            .when().post("/me/work-orders/" + orderA + "/approval")
            .then().statusCode(404);
    }

    @Test
    @Order(12)
    void approval_withoutDecision_shouldBeBadRequest() {
        asClient(clientA)
            .body("{}")
            .when().post("/me/work-orders/" + orderA + "/approval")
            .then().statusCode(400);
    }

    @Test
    @Order(13)
    void approval_whenAwaitingApproval_shouldMoveToExecution() {
        asStaff("ADMIN").when().patch("/admin/work-orders/" + orderA + "/start-diagnosis")
            .then().statusCode(200);
        asStaff("ADMIN").when().patch("/admin/work-orders/" + orderA + "/send-for-approval")
            .then().statusCode(200)
            .body("status", equalTo("AWAITING_APPROVAL"));

        asClient(clientA)
            .body("{\"decision\": \"APPROVE\"}")
            .when().post("/me/work-orders/" + orderA + "/approval")
            .then().statusCode(200)
            .body("status", equalTo("IN_EXECUTION"));
    }

    @Test
    @Order(14)
    void clientTokenWithoutClientIdClaim_shouldBeForbidden() {
        given().auth().oauth2(TestTokens.clientWithoutIdentity())
            .when().get("/me")
            .then().statusCode(403);
    }
}
