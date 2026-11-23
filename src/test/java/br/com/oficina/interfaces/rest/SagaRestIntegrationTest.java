package br.com.oficina.interfaces.rest;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.nullValue;

import br.com.oficina.testsupport.SagaEnabledProfile;
import br.com.oficina.testsupport.TestDocuments;
import br.com.oficina.testsupport.TestTokens;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import io.restassured.specification.RequestSpecification;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Rotas da Fase 4 no OS, com tokens reais e a Saga ligada: histórico (funcionário e cliente),
 * consulta e operação da Saga, e o 410 das decisões que passaram para o Billing.
 */
@QuarkusTest
@TestProfile(SagaEnabledProfile.class)
class SagaRestIntegrationTest {

    private static Long ownerId;
    private static Long otherClientId;
    private static Long workOrderId;
    private static String sagaId;

    /** Uma vez por classe, mas depois de a aplicação subir (um {@code @BeforeAll} estático roda antes do Quarkus). */
    @BeforeEach
    void openWorkOrderAndStartTheSagaOnce() {
        if (workOrderId != null) {
            return;
        }
        String ownerCpf = TestDocuments.cpf();
        ownerId = createClient("Dona da OS", ownerCpf);
        otherClientId = createClient("Outra cliente", TestDocuments.cpf());
        Long vehicleId = asStaff("ADMIN")
            .body("""
                {"licensePlate": "%s", "brand": "Fiat", "model": "Argo", "productionYear": 2021, "clientId": %d}
                """.formatted(TestDocuments.plate(), ownerId))
            .when().post("/admin/vehicles")
            .then().statusCode(201).extract().<Integer>path("id").longValue();
        workOrderId = asStaff("ADMIN")
            .body("""
                {"clientCpfCnpj": "%s", "vehicleId": %d, "notes": "revisão"}
                """.formatted(ownerCpf, vehicleId))
            .when().post("/admin/work-orders")
            .then().statusCode(201).extract().<Integer>path("id").longValue();

        asStaff("MECHANIC").when().patch("/admin/work-orders/{id}/start-diagnosis", workOrderId).then().statusCode(200);
        asStaff("MECHANIC").when().patch("/admin/work-orders/{id}/send-for-approval", workOrderId)
            .then().statusCode(200).body("status", equalTo("AWAITING_APPROVAL"));

        sagaId = asStaff("MECHANIC").when().get("/admin/work-orders/{id}/saga", workOrderId)
            .then().statusCode(200).extract().path("id");
    }

    @Test
    void workOrderSaga_shouldExposeStateAndSteps() {
        asStaff("MECHANIC").when().get("/admin/work-orders/{id}/saga", workOrderId)
            .then().statusCode(200)
            .body("state", equalTo("BUDGET_REQUESTED"))
            .body("workOrderId", equalTo(workOrderId.intValue()))
            .body("steps.step", contains("SagaStarted"));
    }

    @Test
    void sagaById_shouldReturnTheSameSaga() {
        asStaff("ADMIN").when().get("/admin/sagas/{id}", sagaId)
            .then().statusCode(200).body("id", equalTo(sagaId));
    }

    @Test
    void unknownSaga_shouldReturn404() {
        asStaff("ADMIN").when().get("/admin/sagas/{id}", UUID.randomUUID()).then().statusCode(404);
    }

    @Test
    void retryCompensation_shouldRequireAdminAndAParkedSaga() {
        asStaff("MECHANIC").when().post("/admin/sagas/{id}/retry-compensation", sagaId).then().statusCode(403);
        asStaff("ADMIN").when().post("/admin/sagas/{id}/retry-compensation", sagaId)
            .then().statusCode(422).body("message", containsString("COMPENSATION_PENDING"));
    }

    @Test
    void directApproval_shouldBeGoneAndPointToTheBillingService() {
        asStaff("ADMIN").when().patch("/admin/work-orders/{id}/approve", workOrderId)
            .then().statusCode(410).body("message", containsString("/admin/budgets/"));
        asStaff("ADMIN").when().patch("/admin/work-orders/{id}/complete", workOrderId)
            .then().statusCode(410).body("message", containsString("/admin/executions/"));
    }

    @Test
    void staffHistory_shouldListEveryTransitionWithItsActor() {
        asStaff("MECHANIC").when().get("/admin/work-orders/{id}/history", workOrderId)
            .then().statusCode(200)
            .body("toStatus", contains("RECEIVED", "IN_DIAGNOSIS", "AWAITING_APPROVAL"))
            .body("source", everyItem(equalTo("USER")))
            .body("actor", hasItem("staff-mechanic"));
    }

    @Test
    void clientHistory_shouldHideTheStaffAndOtherClientsOrders() {
        asClient(ownerId).when().get("/me/work-orders/{id}/history", workOrderId)
            .then().statusCode(200)
            .body("toStatusLabel", contains("Recebida", "Diagnóstico", "Aguardando Aprovação"))
            .body("[0].actor", nullValue());
        asClient(otherClientId).when().get("/me/work-orders/{id}/history", workOrderId).then().statusCode(404);
    }

    @Test
    void clientToken_shouldNotReachTheSagaAdministration() {
        asClient(ownerId).when().get("/admin/sagas/{id}", sagaId).then().statusCode(403);
        asClient(ownerId).when().get("/admin/work-orders/{id}/history", workOrderId).then().statusCode(403);
    }

    private static Long createClient(String name, String cpf) {
        return asStaff("ADMIN")
            .body("""
                {"name": "%s", "cpfCnpj": "%s", "clientType": "PF", "email": "%s@x.com"}
                """.formatted(name, cpf, cpf))
            .when().post("/admin/clients")
            .then().statusCode(201).extract().<Integer>path("id").longValue();
    }

    private static RequestSpecification asStaff(String role) {
        return given().auth().oauth2(TestTokens.staff(role)).contentType(ContentType.JSON);
    }

    private static RequestSpecification asClient(Long clientId) {
        return given().auth().oauth2(TestTokens.client(clientId)).contentType(ContentType.JSON);
    }
}
