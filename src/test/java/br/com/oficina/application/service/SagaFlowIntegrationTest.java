package br.com.oficina.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.oficina.application.OperationMovedException;
import br.com.oficina.application.dto.SagaViewDto;
import br.com.oficina.application.dto.StatusHistoryEntryDto;
import br.com.oficina.application.dto.WorkOrderCreateDto;
import br.com.oficina.application.dto.WorkOrderPartDto;
import br.com.oficina.application.dto.WorkOrderResponseDto;
import br.com.oficina.application.dto.WorkOrderServiceDto;
import br.com.oficina.application.messaging.InboundMessage;
import br.com.oficina.application.messaging.MessageKind;
import br.com.oficina.application.ports.in.ChangeWorkOrderStatusUseCase;
import br.com.oficina.application.ports.in.CreateWorkOrderUseCase;
import br.com.oficina.application.ports.in.HandleSagaMessageUseCase;
import br.com.oficina.application.ports.in.HandleSagaMessageUseCase.Result;
import br.com.oficina.application.ports.in.ListWorkOrdersUseCase;
import br.com.oficina.application.ports.in.SagaAdministrationUseCase;
import br.com.oficina.application.ports.in.WorkOrderHistoryUseCase;
import br.com.oficina.application.ports.out.ClientRepositoryPort;
import br.com.oficina.application.ports.out.PartRepositoryPort;
import br.com.oficina.application.ports.out.ServiceItemRepositoryPort;
import br.com.oficina.application.ports.out.VehicleRepositoryPort;
import br.com.oficina.domain.model.Client;
import br.com.oficina.domain.model.ClientType;
import br.com.oficina.domain.model.Part;
import br.com.oficina.domain.model.ServiceItem;
import br.com.oficina.domain.model.Vehicle;
import br.com.oficina.infrastructure.persistence.OutboxMessageEntity;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import br.com.oficina.testsupport.SagaEnabledProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * A Saga de ponta a ponta dentro do OS, com PostgreSQL real: V7, adapters JPA, outbox, inbox e
 * histórico. As respostas do Billing e do Execution são injetadas pela porta de entrada do consumidor
 * (o mesmo caminho do SQS, sem o broker) — o que se prova aqui é que cada resposta move a OS, grava o
 * passo e enfileira o próximo comando na mesma transação.
 */
@QuarkusTest
@TestProfile(SagaEnabledProfile.class)
class SagaFlowIntegrationTest {

    private static final AtomicInteger SEQUENCE = new AtomicInteger();
    private static final int INITIAL_STOCK = 10;

    @Inject CreateWorkOrderUseCase createWorkOrder;
    @Inject ChangeWorkOrderStatusUseCase changeStatus;
    @Inject ListWorkOrdersUseCase workOrders;
    @Inject HandleSagaMessageUseCase replies;
    @Inject SagaAdministrationUseCase sagas;
    @Inject WorkOrderHistoryUseCase history;
    @Inject ClientRepositoryPort clients;
    @Inject VehicleRepositoryPort vehicles;
    @Inject PartRepositoryPort parts;
    @Inject ServiceItemRepositoryPort serviceItems;
    @Inject EntityManager em;

    private Long workOrderId;
    private Long partId;

    @BeforeEach
    void openWorkOrderInDiagnosis() {
        int n = SEQUENCE.incrementAndGet();
        String document = String.format("9%010d", System.nanoTime() % 10_000_000_000L + n);
        QuarkusTransaction.requiringNew().run(() -> {
            Client client = clients.save(new Client("Cliente Saga " + n, document, ClientType.PF, "saga" + n + "@x.com", null));
            Vehicle vehicle = vehicles.save(new Vehicle(plate(n), "Fiat", "Argo", 2021, client.getId()));
            partId = parts.save(new Part("Pastilha " + n, null, new BigDecimal("89.90"), INITIAL_STOCK, "UN")).getId();
            Long serviceId = serviceItems.save(new ServiceItem("Troca de pastilhas " + n, null, new BigDecimal("150.00"), 60)).getId();
            WorkOrderResponseDto created = createWorkOrder.create(new WorkOrderCreateDto(document, vehicle.getId(),
                "barulho no freio", List.of(new WorkOrderServiceDto(serviceId, null)), List.of(new WorkOrderPartDto(partId, 2))));
            workOrderId = created.id();
        });
        changeStatus.startDiagnosis(workOrderId);
    }

    @Test
    void happyPath_shouldDriveTheOrderThroughEveryParticipantUntilFinished() {
        WorkOrderResponseDto sent = changeStatus.sendForApproval(workOrderId);
        assertThat(sent.status().name()).isEqualTo("AWAITING_APPROVAL");
        UUID sagaId = saga().id();
        assertThat(outboxTypes()).containsExactlyInAnyOrder("DiagnosisStarted", "GenerateBudget");

        assertThat(reply(sagaId, "BudgetGenerated", Map.of("budgetId", "b-1", "total", "329.80",
            "expiresAt", "2026-11-26T14:00:00Z"))).isEqualTo(Result.PROCESSED);
        reply(sagaId, "BudgetApproved", Map.of("budgetId", "b-1", "by", "CLIENT_TOKEN"));
        assertThat(outboxTypes()).contains("RequestPayment");
        assertThat(status()).isEqualTo("AWAITING_APPROVAL");

        reply(sagaId, "PaymentRequested", Map.of("paymentId", "p-1", "expiresAt", "2026-11-23T15:00:00Z"));
        assertThat(status()).isEqualTo("AWAITING_PAYMENT");

        reply(sagaId, "PaymentApproved", Map.of("paymentId", "p-1", "amount", "329.80"));
        assertThat(outboxTypes()).contains("EnqueueExecution");

        reply(sagaId, "ExecutionEnqueued", Map.of("position", 1));
        assertThat(status()).isEqualTo("AWAITING_EXECUTION");
        reply(sagaId, "ExecutionStarted", Map.of("assignee", "mecanico"));
        assertThat(status()).isEqualTo("IN_EXECUTION");
        reply(sagaId, "ExecutionFinished", Map.of("summary", "pastilhas trocadas"));

        assertThat(status()).isEqualTo("FINISHED");
        SagaViewDto saga = fresh(() -> sagas.findById(sagaId));
        assertThat(saga.state()).isEqualTo("COMPLETED");
        assertThat(saga.pendingCompensations()).isEmpty();
        assertThat(saga.steps()).extracting(SagaViewDto.Step::step).containsExactly("SagaStarted", "BudgetGenerated",
            "BudgetApproved", "PaymentRequested", "PaymentApproved", "ExecutionEnqueued", "ExecutionStarted",
            "ExecutionFinished");

        List<StatusHistoryEntryDto> transitions = fresh(() -> history.history(workOrderId));
        assertThat(transitions).extracting(StatusHistoryEntryDto::toStatus).containsExactly("RECEIVED", "IN_DIAGNOSIS",
            "AWAITING_APPROVAL", "AWAITING_PAYMENT", "AWAITING_EXECUTION", "IN_EXECUTION", "FINISHED");
        assertThat(transitions).filteredOn(t -> "SAGA".equals(t.source())).hasSize(4);
    }

    @Test
    void executionFailureAfterPayment_shouldRefundVoidAndCancelRestoringStock() {
        UUID sagaId = advanceToInExecution();

        reply(sagaId, "ExecutionFailed", Map.of("reason", "peça indisponível"));
        assertThat(lastOutboxType()).isEqualTo("RefundPayment");
        assertThat(status()).isEqualTo("IN_EXECUTION");

        reply(sagaId, "PaymentRefunded", Map.of("paymentId", "p-1"));
        assertThat(lastOutboxType()).isEqualTo("VoidBudget");

        reply(sagaId, "BudgetVoided", Map.of("budgetId", "b-1"));

        assertThat(status()).isEqualTo("CANCELLED");
        assertThat(fresh(() -> sagas.findById(sagaId)).state()).isEqualTo("CANCELLED");
        assertThat(stock()).isEqualTo(INITIAL_STOCK);
        assertThat(fresh(() -> history.history(workOrderId))).last()
            .satisfies(entry -> assertThat(entry.reason()).contains("peça indisponível"));
    }

    @Test
    void duplicatedReply_shouldBeProcessedOnlyOnce() {
        changeStatus.sendForApproval(workOrderId);
        UUID sagaId = saga().id();
        UUID messageId = UUID.randomUUID();
        InboundMessage generated = InboundMessage.of(messageId, "BudgetGenerated", MessageKind.EVENT, sagaId,
            workOrderId, Map.of("budgetId", "b-1", "total", "329.80", "expiresAt", "2026-11-26T14:00:00Z"));

        assertThat(replies.handle(generated)).isEqualTo(Result.PROCESSED);
        assertThat(replies.handle(generated)).isEqualTo(Result.DUPLICATE);

        assertThat(fresh(() -> sagas.findById(sagaId)).steps()).hasSize(2);
    }

    @Test
    void manualCancellationWithActiveSaga_shouldCompensateBeforeCancelling() {
        changeStatus.sendForApproval(workOrderId);
        UUID sagaId = saga().id();
        reply(sagaId, "BudgetGenerated", Map.of("budgetId", "b-1", "total", "329.80", "expiresAt", "2026-11-26T14:00:00Z"));

        WorkOrderResponseDto afterRequest = changeStatus.cancel(workOrderId);

        assertThat(afterRequest.status().name()).isEqualTo("AWAITING_APPROVAL");
        assertThat(lastOutboxType()).isEqualTo("VoidBudget");
        reply(sagaId, "BudgetVoided", Map.of("budgetId", "b-1"));
        assertThat(status()).isEqualTo("CANCELLED");
        assertThat(stock()).isEqualTo(INITIAL_STOCK);
    }

    @Test
    void directDecisionOnASagaOrder_shouldPointToTheBillingService() {
        changeStatus.sendForApproval(workOrderId);

        assertThatThrownBy(() -> changeStatus.approve(workOrderId))
            .isInstanceOf(OperationMovedException.class)
            .hasMessageContaining("/admin/budgets/");
        assertThat(status()).isEqualTo("AWAITING_APPROVAL");
    }

    @Test
    void overdueStep_shouldBeRetriedByTheTimeoutJob() {
        changeStatus.sendForApproval(workOrderId);
        UUID sagaId = saga().id();
        QuarkusTransaction.requiringNew().run(() -> em.createNativeQuery(
                "UPDATE saga_instance SET deadline_at = :past WHERE id = :id")
            .setParameter("past", OffsetDateTime.now().minusMinutes(1))
            .setParameter("id", sagaId)
            .executeUpdate());

        assertThat(sagas.overdueIds(100)).contains(sagaId);
        sagas.expire(sagaId);

        assertThat(outboxTypes()).filteredOn("GenerateBudget"::equals).hasSize(2);
        assertThat(fresh(() -> sagas.findById(sagaId)).steps()).extracting(SagaViewDto.Step::outcome).contains("RETRIED");
    }

    @Test
    void unknownReply_shouldBeAcknowledgedWithoutTouchingTheSaga() {
        changeStatus.sendForApproval(workOrderId);
        UUID sagaId = saga().id();

        assertThat(reply(sagaId, "BudgetDiscountApplied", Map.of())).isEqualTo(Result.SKIPPED);
        assertThat(reply(UUID.randomUUID(), "BudgetVoided", Map.of("budgetId", "x"))).isEqualTo(Result.SKIPPED);
        assertThat(fresh(() -> sagas.findById(sagaId)).state()).isEqualTo("BUDGET_REQUESTED");
    }

    // ===== Ajudantes =====

    private UUID advanceToInExecution() {
        changeStatus.sendForApproval(workOrderId);
        UUID sagaId = saga().id();
        reply(sagaId, "BudgetGenerated", Map.of("budgetId", "b-1", "total", "329.80", "expiresAt", "2026-11-26T14:00:00Z"));
        reply(sagaId, "BudgetApproved", Map.of("budgetId", "b-1", "by", "STAFF"));
        reply(sagaId, "PaymentRequested", Map.of("paymentId", "p-1", "expiresAt", "2026-11-23T15:00:00Z"));
        reply(sagaId, "PaymentApproved", Map.of("paymentId", "p-1", "amount", "329.80"));
        reply(sagaId, "ExecutionEnqueued", Map.of("position", 1));
        reply(sagaId, "ExecutionStarted", Map.of("assignee", "mecanico"));
        return sagaId;
    }

    private Result reply(UUID sagaId, String type, Map<String, Object> payload) {
        return replies.handle(InboundMessage.of(UUID.randomUUID(), type, MessageKind.EVENT, sagaId, workOrderId, payload));
    }

    private SagaViewDto saga() {
        return fresh(() -> sagas.findLatestForWorkOrder(workOrderId));
    }

    private String status() {
        return fresh(() -> workOrders.findById(workOrderId).status().name());
    }

    private int stock() {
        return QuarkusTransaction.requiringNew().call(() -> parts.fetchById(partId).orElseThrow().getStockQuantity());
    }

    private List<String> outboxTypes() {
        return outbox().stream().map(OutboxMessageEntity::getMessageType).toList();
    }

    private String lastOutboxType() {
        List<OutboxMessageEntity> messages = outbox();
        return messages.get(messages.size() - 1).getMessageType();
    }

    private List<OutboxMessageEntity> outbox() {
        return QuarkusTransaction.requiringNew().call(() -> em.createQuery(
                "FROM OutboxMessageEntity o WHERE o.messageGroupId = :group ORDER BY o.createdAt, o.id",
                OutboxMessageEntity.class)
            .setParameter("group", "wo-" + workOrderId)
            .getResultList());
    }

    /** Leitura numa transação nova: fora dela, a sessão do contexto de requisição do teste devolveria entidades em cache. */
    private static <T> T fresh(java.util.function.Supplier<T> read) {
        return QuarkusTransaction.requiringNew().call(read::get);
    }

    private static String plate(int n) {
        char first = (char) ('A' + (n / 26) % 26);
        char second = (char) ('A' + n % 26);
        return "SG" + first + (n % 10) + second + String.format("%02d", (int) (System.nanoTime() % 100));
    }
}
