package br.com.oficina.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.oficina.application.OperationMovedException;
import br.com.oficina.application.dto.SagaViewDto;
import br.com.oficina.application.dto.StatusHistoryEntryDto;
import br.com.oficina.application.messaging.InboundMessage;
import br.com.oficina.application.messaging.MessageKind;
import br.com.oficina.application.ports.in.ChangeWorkOrderStatusUseCase;
import br.com.oficina.application.ports.in.HandleSagaMessageUseCase.Result;
import br.com.oficina.domain.model.WorkOrderStatus;
import br.com.oficina.testsupport.SagaEnabledProfile;
import br.com.oficina.testsupport.SagaScenario;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * A Saga de ponta a ponta dentro do OS, com PostgreSQL real: V7, adapters JPA, outbox, inbox e
 * histórico. As respostas do Billing e do Execution entram pela porta de entrada do consumidor (o mesmo
 * caminho do SQS, sem o broker) — o que se prova é que cada resposta move a OS, grava o passo e
 * enfileira o próximo comando na mesma transação. Os cenários de negócio estão também em BDD
 * ({@code features/saga}); aqui ficam as verificações técnicas.
 */
@QuarkusTest
@TestProfile(SagaEnabledProfile.class)
class SagaFlowIntegrationTest {

    private static final int INITIAL_STOCK = 10;
    private static final Map<String, Object> BUDGET = Map.of("budgetId", "b-1", "total", "329.80",
        "expiresAt", "2026-11-26T14:00:00Z");

    @Inject ChangeWorkOrderStatusUseCase changeStatus;

    private SagaScenario scenario;

    @BeforeEach
    void openWorkOrderInDiagnosis() {
        scenario = new SagaScenario().openWorkOrderInDiagnosis(new BigDecimal("150.00"), 2, new BigDecimal("89.90"),
            INITIAL_STOCK);
    }

    @Test
    void happyPath_shouldDriveTheOrderThroughEveryParticipantUntilFinished() {
        scenario.sendForApproval();
        assertThat(scenario.status()).isEqualTo(WorkOrderStatus.AWAITING_APPROVAL);
        assertThat(scenario.sentCommandTypes()).containsExactlyInAnyOrder("DiagnosisStarted", "GenerateBudget");

        assertThat(scenario.reply("BudgetGenerated", BUDGET)).isEqualTo(Result.PROCESSED);
        scenario.reply("BudgetApproved", Map.of("budgetId", "b-1", "by", "CLIENT_TOKEN"));
        assertThat(scenario.lastCommand().type()).isEqualTo("RequestPayment");
        assertThat(scenario.lastCommand().text("amount")).isEqualTo("329.80");
        assertThat(scenario.status()).isEqualTo(WorkOrderStatus.AWAITING_APPROVAL);

        scenario.reply("PaymentRequested", Map.of("paymentId", "p-1", "expiresAt", "2026-11-23T15:00:00Z"));
        assertThat(scenario.status()).isEqualTo(WorkOrderStatus.AWAITING_PAYMENT);

        scenario.reply("PaymentApproved", Map.of("paymentId", "p-1", "amount", "329.80"));
        assertThat(scenario.lastCommand().type()).isEqualTo("EnqueueExecution");

        scenario.reply("ExecutionEnqueued", Map.of("position", 1));
        assertThat(scenario.status()).isEqualTo(WorkOrderStatus.AWAITING_EXECUTION);
        scenario.reply("ExecutionStarted", Map.of("assignee", "mecanico"));
        assertThat(scenario.status()).isEqualTo(WorkOrderStatus.IN_EXECUTION);
        scenario.reply("ExecutionFinished", Map.of("summary", "pastilhas trocadas"));

        assertThat(scenario.status()).isEqualTo(WorkOrderStatus.FINISHED);
        SagaViewDto saga = scenario.saga();
        assertThat(saga.state()).isEqualTo("COMPLETED");
        assertThat(saga.pendingCompensations()).isEmpty();
        assertThat(saga.steps()).extracting(SagaViewDto.Step::step).containsExactly("SagaStarted", "BudgetGenerated",
            "BudgetApproved", "PaymentRequested", "PaymentApproved", "ExecutionEnqueued", "ExecutionStarted",
            "ExecutionFinished");

        List<StatusHistoryEntryDto> transitions = scenario.history();
        assertThat(transitions).extracting(StatusHistoryEntryDto::toStatus).containsExactly("RECEIVED", "IN_DIAGNOSIS",
            "AWAITING_APPROVAL", "AWAITING_PAYMENT", "AWAITING_EXECUTION", "IN_EXECUTION", "FINISHED");
        assertThat(transitions).filteredOn(t -> "SAGA".equals(t.source())).hasSize(4);
    }

    @Test
    void executionFailureAfterPayment_shouldRefundVoidAndCancelRestoringStock() {
        advanceToInExecution();

        scenario.reply("ExecutionFailed", Map.of("reason", "peça indisponível"));
        assertThat(scenario.lastCommand().type()).isEqualTo("RefundPayment");
        assertThat(scenario.status()).isEqualTo(WorkOrderStatus.IN_EXECUTION);

        scenario.reply("PaymentRefunded", Map.of("paymentId", "p-1"));
        assertThat(scenario.lastCommand().type()).isEqualTo("VoidBudget");

        scenario.reply("BudgetVoided", Map.of("budgetId", "b-1"));

        assertThat(scenario.status()).isEqualTo(WorkOrderStatus.CANCELLED);
        assertThat(scenario.saga().state()).isEqualTo("CANCELLED");
        assertThat(scenario.stock()).isEqualTo(INITIAL_STOCK);
        assertThat(scenario.history()).last()
            .satisfies(entry -> assertThat(entry.reason()).contains("peça indisponível"));
    }

    @Test
    void duplicatedReply_shouldBeProcessedOnlyOnce() {
        scenario.sendForApproval();
        InboundMessage generated = InboundMessage.of(UUID.randomUUID(), "BudgetGenerated", MessageKind.EVENT,
            scenario.sagaId(), scenario.workOrderId(), BUDGET);

        assertThat(scenario.replyWith(generated)).isEqualTo(Result.PROCESSED);
        assertThat(scenario.replyWith(generated)).isEqualTo(Result.DUPLICATE);

        assertThat(scenario.saga().steps()).hasSize(2);
    }

    @Test
    void manualCancellationWithActiveSaga_shouldCompensateBeforeCancelling() {
        scenario.sendForApproval();
        scenario.reply("BudgetGenerated", BUDGET);

        scenario.cancel();

        assertThat(scenario.status()).isEqualTo(WorkOrderStatus.AWAITING_APPROVAL);
        assertThat(scenario.lastCommand().type()).isEqualTo("VoidBudget");
        scenario.reply("BudgetVoided", Map.of("budgetId", "b-1"));
        assertThat(scenario.status()).isEqualTo(WorkOrderStatus.CANCELLED);
        assertThat(scenario.stock()).isEqualTo(INITIAL_STOCK);
    }

    @Test
    void directDecisionOnASagaOrder_shouldPointToTheBillingService() {
        scenario.sendForApproval();

        assertThatThrownBy(() -> changeStatus.approve(scenario.workOrderId()))
            .isInstanceOf(OperationMovedException.class)
            .hasMessageContaining("/admin/budgets/");
        assertThat(scenario.status()).isEqualTo(WorkOrderStatus.AWAITING_APPROVAL);
    }

    @Test
    void overdueStep_shouldBeRetriedByTheTimeoutJob() {
        scenario.sendForApproval();
        UUID sagaId = scenario.sagaId();
        scenario.expireCurrentStep();

        assertThat(scenario.overdueSagas()).contains(sagaId);
        scenario.runTimeoutJobFor(sagaId);

        assertThat(scenario.sentCommandTypes()).filteredOn("GenerateBudget"::equals).hasSize(2);
        assertThat(scenario.saga().steps()).extracting(SagaViewDto.Step::outcome).contains("RETRIED");
    }

    @Test
    void unknownReply_shouldBeAcknowledgedWithoutTouchingTheSaga() {
        scenario.sendForApproval();

        assertThat(scenario.reply("BudgetDiscountApplied", Map.of())).isEqualTo(Result.SKIPPED);
        assertThat(scenario.replyWith(InboundMessage.of(UUID.randomUUID(), "BudgetVoided", MessageKind.EVENT,
            UUID.randomUUID(), scenario.workOrderId(), Map.of("budgetId", "x")))).isEqualTo(Result.SKIPPED);
        assertThat(scenario.saga().state()).isEqualTo("BUDGET_REQUESTED");
    }

    private void advanceToInExecution() {
        scenario.sendForApproval();
        scenario.reply("BudgetGenerated", BUDGET);
        scenario.reply("BudgetApproved", Map.of("budgetId", "b-1", "by", "STAFF"));
        scenario.reply("PaymentRequested", Map.of("paymentId", "p-1", "expiresAt", "2026-11-23T15:00:00Z"));
        scenario.reply("PaymentApproved", Map.of("paymentId", "p-1", "amount", "329.80"));
        scenario.reply("ExecutionEnqueued", Map.of("position", 1));
        scenario.reply("ExecutionStarted", Map.of("assignee", "mecanico"));
    }
}
