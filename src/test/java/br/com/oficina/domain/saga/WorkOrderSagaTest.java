package br.com.oficina.domain.saga;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.oficina.domain.exception.BusinessException;
import br.com.oficina.domain.saga.SagaCommand.CancelExecution;
import br.com.oficina.domain.saga.SagaCommand.EnqueueExecution;
import br.com.oficina.domain.saga.SagaCommand.GenerateBudget;
import br.com.oficina.domain.saga.SagaCommand.RefundPayment;
import br.com.oficina.domain.saga.SagaCommand.RequestPayment;
import br.com.oficina.domain.saga.SagaCommand.VoidBudget;
import br.com.oficina.domain.saga.SagaEvent.BudgetApproved;
import br.com.oficina.domain.saga.SagaEvent.BudgetExpired;
import br.com.oficina.domain.saga.SagaEvent.BudgetGenerated;
import br.com.oficina.domain.saga.SagaEvent.BudgetGenerationFailed;
import br.com.oficina.domain.saga.SagaEvent.BudgetRejected;
import br.com.oficina.domain.saga.SagaEvent.BudgetVoided;
import br.com.oficina.domain.saga.SagaEvent.ExecutionCancelled;
import br.com.oficina.domain.saga.SagaEvent.ExecutionEnqueueFailed;
import br.com.oficina.domain.saga.SagaEvent.ExecutionEnqueued;
import br.com.oficina.domain.saga.SagaEvent.ExecutionFailed;
import br.com.oficina.domain.saga.SagaEvent.ExecutionFinished;
import br.com.oficina.domain.saga.SagaEvent.ExecutionStarted;
import br.com.oficina.domain.saga.SagaEvent.PaymentApproved;
import br.com.oficina.domain.saga.SagaEvent.PaymentExpired;
import br.com.oficina.domain.saga.SagaEvent.PaymentRefundFailed;
import br.com.oficina.domain.saga.SagaEvent.PaymentRefunded;
import br.com.oficina.domain.saga.SagaEvent.PaymentRejected;
import br.com.oficina.domain.saga.SagaEvent.PaymentRequestFailed;
import br.com.oficina.domain.saga.SagaEvent.PaymentRequested;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * A máquina de estados da Saga (spec 04 §4): cada passo no caminho feliz, na falha e no timeout, mais
 * as regras transversais (§5) — idempotência por estado, pagamento órfão, cancelamento manual e
 * compensação parada. Domínio puro: sem Quarkus, sem banco, relógio fixo.
 */
class WorkOrderSagaTest {

    private static final SagaPolicy POLICY = new SagaPolicy(3, 3, Duration.ofSeconds(30), Duration.ofHours(72),
        Duration.ofMinutes(30));
    private static final OffsetDateTime T0 = OffsetDateTime.parse("2026-11-23T12:00:00Z");

    private WorkOrderSaga saga;
    private OffsetDateTime now;

    @BeforeEach
    void setUp() {
        saga = WorkOrderSaga.newFor(UUID.randomUUID(), 10L);
        now = T0;
    }

    // ===== Início =====

    @Test
    void begin_shouldRequestTheBudgetAndWaitForTheReply() {
        SagaDecision decision = saga.begin(ctx());

        assertThat(decision.outcome()).isEqualTo(StepOutcome.STARTED);
        assertThat(decision.commands()).containsExactly(new GenerateBudget());
        assertThat(saga.state()).isEqualTo(SagaState.BUDGET_REQUESTED);
        assertThat(saga.deadlineAt()).isEqualTo(T0.plusSeconds(30));
        assertThat(saga.createdAt()).isEqualTo(T0);
    }

    @Test
    void begin_twice_shouldBeRejected() {
        saga.begin(ctx());
        assertThatThrownBy(() -> saga.begin(ctx())).isInstanceOf(BusinessException.class);
    }

    @Test
    void events_beforeBegin_shouldBeRejected() {
        assertThatThrownBy(() -> saga.on(new BudgetGenerated("b-1"), ctx())).isInstanceOf(BusinessException.class);
    }

    // ===== Passo 1 — orçamento =====

    @Nested
    @DisplayName("passo 1: orçamento pedido ao Billing")
    class BudgetRequested {

        @BeforeEach
        void start() {
            saga.begin(ctx());
        }

        @Test
        void budgetGenerated_shouldAwaitApprovalAndStackTheVoid() {
            SagaDecision decision = saga.on(new BudgetGenerated("b-1"), ctx());

            assertThat(decision.outcome()).isEqualTo(StepOutcome.ADVANCED);
            assertThat(decision.commands()).isEmpty();
            assertThat(saga.state()).isEqualTo(SagaState.AWAITING_APPROVAL);
            assertThat(saga.budgetId()).isEqualTo("b-1");
            assertThat(saga.pendingCompensations()).containsExactly(Compensation.VOID_BUDGET);
            assertThat(saga.deadlineAt()).isEqualTo(T0.plusHours(72));
        }

        @Test
        void generationFailed_shouldAbortAndSendTheOrderBackToDiagnosis() {
            SagaDecision decision = saga.on(new BudgetGenerationFailed("sem itens"), ctx());

            assertThat(decision.outcome()).isEqualTo(StepOutcome.ABORTED);
            assertThat(decision.effect()).isEqualTo(OrderEffect.REVERT_TO_DIAGNOSIS);
            assertThat(saga.state()).isEqualTo(SagaState.CANCELLED);
            assertThat(saga.failureReason()).contains("sem itens");
        }

        @Test
        void timeout_shouldResendUntilTheLimitThenAbort() {
            SagaDecision first = expireStep();
            SagaDecision second = expireStep();
            SagaDecision third = expireStep();

            assertThat(first.outcome()).isEqualTo(StepOutcome.RETRIED);
            assertThat(first.commands()).containsExactly(new GenerateBudget());
            assertThat(second.outcome()).isEqualTo(StepOutcome.RETRIED);
            assertThat(third.outcome()).isEqualTo(StepOutcome.ABORTED);
            assertThat(third.effect()).isEqualTo(OrderEffect.REVERT_TO_DIAGNOSIS);
        }

        @Test
        void timeout_beforeTheDeadline_shouldBeIgnored() {
            now = T0.plusSeconds(10);
            assertThat(saga.onTimeout(ctx()).isIgnored()).isTrue();
            assertThat(saga.state()).isEqualTo(SagaState.BUDGET_REQUESTED);
        }

        @Test
        void unrelatedEvent_shouldBeIgnoredWithoutChangingState() {
            SagaDecision decision = saga.on(new ExecutionFinished("ok"), ctx());

            assertThat(decision.isIgnored()).isTrue();
            assertThat(decision.detail()).contains("ExecutionFinished").contains("BUDGET_REQUESTED");
            assertThat(saga.state()).isEqualTo(SagaState.BUDGET_REQUESTED);
        }
    }

    // ===== Passo 2 — aprovação =====

    @Nested
    @DisplayName("passo 2: aguardando a decisão do cliente")
    class AwaitingApproval {

        @BeforeEach
        void reach() {
            toAwaitingApproval();
        }

        @Test
        void approved_shouldRequestTheFirstPayment() {
            SagaDecision decision = saga.on(new BudgetApproved("b-1", "CLIENT_TOKEN"), ctx());

            assertThat(decision.commands()).containsExactly(new RequestPayment("b-1", 1));
            assertThat(saga.state()).isEqualTo(SagaState.PAYMENT_REQUESTED);
            assertThat(saga.paymentAttempts()).isEqualTo(1);
        }

        @Test
        void rejected_shouldVoidTheBudget() {
            SagaDecision decision = saga.on(new BudgetRejected("b-1", "CLIENT_JWT"), ctx());

            assertThat(decision.outcome()).isEqualTo(StepOutcome.COMPENSATING);
            assertThat(decision.commands()).containsExactly(new VoidBudget("b-1", "orçamento recusado (CLIENT_JWT)"));
            assertThat(saga.state()).isEqualTo(SagaState.COMPENSATING);
        }

        @Test
        void expired_shouldVoidTheBudget() {
            SagaDecision decision = saga.on(new BudgetExpired("b-1"), ctx());
            assertThat(decision.commands()).singleElement().isInstanceOf(VoidBudget.class);
        }

        @Test
        void approvalDeadline_shouldCompensate() {
            now = T0.plusHours(73);
            SagaDecision decision = saga.onTimeout(ctx());

            assertThat(decision.outcome()).isEqualTo(StepOutcome.COMPENSATING);
            assertThat(decision.commands()).singleElement().isInstanceOf(VoidBudget.class);
        }

        @Test
        void voidConfirmed_shouldCancelTheOrder() {
            saga.on(new BudgetRejected("b-1", "STAFF"), ctx());
            SagaDecision decision = saga.on(new BudgetVoided("b-1"), ctx());

            assertThat(decision.outcome()).isEqualTo(StepOutcome.CANCELLED);
            assertThat(decision.effect()).isEqualTo(OrderEffect.CANCEL);
            assertThat(saga.state()).isEqualTo(SagaState.CANCELLED);
            assertThat(saga.pendingCompensations()).isEmpty();
            assertThat(saga.deadlineAt()).isNull();
        }
    }

    // ===== Passo 3 — cobrança =====

    @Nested
    @DisplayName("passo 3: cobrança pedida ao Billing")
    class PaymentRequestedStep {

        @BeforeEach
        void reach() {
            toAwaitingApproval();
            saga.on(new BudgetApproved("b-1", "CLIENT_TOKEN"), ctx());
        }

        @Test
        void paymentRequested_shouldMoveTheOrderToAwaitingPayment() {
            SagaDecision decision = saga.on(new PaymentRequested("p-1"), ctx());

            assertThat(decision.effect()).isEqualTo(OrderEffect.AWAIT_PAYMENT);
            assertThat(saga.state()).isEqualTo(SagaState.AWAITING_PAYMENT);
            assertThat(saga.paymentId()).isEqualTo("p-1");
            assertThat(saga.deadlineAt()).isEqualTo(T0.plusMinutes(30));
        }

        @Test
        void requestFailed_shouldVoidTheBudget() {
            SagaDecision decision = saga.on(new PaymentRequestFailed("Mercado Pago indisponível"), ctx());
            assertThat(decision.commands()).singleElement().isInstanceOf(VoidBudget.class);
        }

        @Test
        void timeout_shouldResendTheSameAttempt() {
            SagaDecision decision = expireStep();

            // Mesma tentativa = mesma chave de idempotência no Billing: não cria uma segunda cobrança.
            assertThat(decision.commands()).containsExactly(new RequestPayment("b-1", 1));
            assertThat(saga.paymentAttempts()).isEqualTo(1);
        }

        @Test
        void timeout_beyondTheLimit_shouldCompensate() {
            expireStep();
            expireStep();
            SagaDecision decision = expireStep();

            assertThat(decision.outcome()).isEqualTo(StepOutcome.COMPENSATING);
        }
    }

    // ===== Passo 4 — pagamento =====

    @Nested
    @DisplayName("passo 4: aguardando o pagamento do Pix")
    class AwaitingPayment {

        @BeforeEach
        void reach() {
            toAwaitingPayment();
        }

        @Test
        void approved_shouldEnqueueTheExecutionAndStackTheRefund() {
            SagaDecision decision = saga.on(new PaymentApproved("p-1"), ctx());

            assertThat(decision.commands()).containsExactly(new EnqueueExecution());
            assertThat(saga.state()).isEqualTo(SagaState.EXECUTION_REQUESTED);
            assertThat(saga.paymentCaptured()).isTrue();
            assertThat(saga.pendingCompensations()).containsExactly(Compensation.REFUND_PAYMENT, Compensation.VOID_BUDGET);
        }

        @Test
        void rejected_shouldIssueANewPixWithoutMovingTheOrderAgain() {
            SagaDecision retry = saga.on(new PaymentRejected("p-1", "rejected_by_bank"), ctx());
            SagaDecision secondPix = saga.on(new PaymentRequested("p-2"), ctx());

            assertThat(retry.outcome()).isEqualTo(StepOutcome.RETRIED);
            assertThat(retry.commands()).containsExactly(new RequestPayment("b-1", 2));
            assertThat(secondPix.effect()).isEqualTo(OrderEffect.NONE);
            assertThat(saga.paymentId()).isEqualTo("p-2");
        }

        @Test
        void expiredAfterTheLastAttempt_shouldVoidTheBudget() {
            saga.on(new PaymentExpired("p-1"), ctx());
            saga.on(new PaymentRequested("p-2"), ctx());
            saga.on(new PaymentExpired("p-2"), ctx());
            saga.on(new PaymentRequested("p-3"), ctx());
            SagaDecision decision = saga.on(new PaymentExpired("p-3"), ctx());

            assertThat(decision.outcome()).isEqualTo(StepOutcome.COMPENSATING);
            assertThat(decision.detail()).contains("tentativas esgotadas");
            assertThat(decision.commands()).singleElement().isInstanceOf(VoidBudget.class);
        }

        @Test
        void rejectionOfAPreviousPix_shouldNotCount() {
            saga.on(new PaymentRejected("p-1", "x"), ctx());
            saga.on(new PaymentRequested("p-2"), ctx());

            SagaDecision stale = saga.on(new PaymentRejected("p-1", "repetido"), ctx());

            assertThat(stale.isIgnored()).isTrue();
            assertThat(saga.paymentAttempts()).isEqualTo(2);
        }

        @Test
        void paymentDeadline_shouldIssueANewPix() {
            now = T0.plusMinutes(31);
            SagaDecision decision = saga.onTimeout(ctx());
            assertThat(decision.commands()).containsExactly(new RequestPayment("b-1", 2));
        }
    }

    // ===== Passos 5 a 7 — execução =====

    @Nested
    @DisplayName("passos 5 a 7: execução")
    class Execution {

        @BeforeEach
        void reach() {
            toAwaitingPayment();
            saga.on(new PaymentApproved("p-1"), ctx());
        }

        @Test
        void enqueued_shouldQueueTheOrderWithoutDeadline() {
            SagaDecision decision = saga.on(new ExecutionEnqueued(2), ctx());

            assertThat(decision.effect()).isEqualTo(OrderEffect.QUEUE_FOR_EXECUTION);
            assertThat(saga.state()).isEqualTo(SagaState.AWAITING_EXECUTION);
            assertThat(saga.deadlineAt()).isNull();
            assertThat(saga.pendingCompensations()).startsWith(Compensation.CANCEL_EXECUTION);
        }

        @Test
        void enqueueFailed_shouldRefundThenVoidThenCancel() {
            SagaDecision refund = saga.on(new ExecutionEnqueueFailed("tabela indisponível"), ctx());
            SagaDecision voiding = saga.on(new PaymentRefunded("p-1"), ctx());
            SagaDecision cancelled = saga.on(new BudgetVoided("b-1"), ctx());

            assertThat(refund.commands()).singleElement().isInstanceOf(RefundPayment.class);
            assertThat(voiding.commands()).singleElement().isInstanceOf(VoidBudget.class);
            assertThat(cancelled.effect()).isEqualTo(OrderEffect.CANCEL);
        }

        @Test
        void enqueueTimeout_shouldResendTheCommand() {
            assertThat(expireStep().commands()).containsExactly(new EnqueueExecution());
        }

        @Test
        void happyPath_shouldCompleteTheSaga() {
            saga.on(new ExecutionEnqueued(1), ctx());
            SagaDecision started = saga.on(new ExecutionStarted("mecanico"), ctx());
            SagaDecision finished = saga.on(new ExecutionFinished("pastilhas trocadas"), ctx());

            assertThat(started.effect()).isEqualTo(OrderEffect.START_EXECUTION);
            assertThat(finished.outcome()).isEqualTo(StepOutcome.COMPLETED);
            assertThat(finished.effect()).isEqualTo(OrderEffect.COMPLETE);
            assertThat(saga.state()).isEqualTo(SagaState.COMPLETED);
            assertThat(saga.pendingCompensations()).isEmpty();
        }

        @Test
        void executionFailure_shouldRefundAndVoidButNotCancelTheFailedTask() {
            saga.on(new ExecutionEnqueued(1), ctx());
            saga.on(new ExecutionStarted("mecanico"), ctx());

            SagaDecision refund = saga.on(new ExecutionFailed("peça indisponível"), ctx());

            assertThat(refund.commands()).containsExactly(
                new RefundPayment("p-1", "falha na execução: peça indisponível"));
            assertThat(saga.pendingCompensations())
                .containsExactly(Compensation.REFUND_PAYMENT, Compensation.VOID_BUDGET);
        }

        @Test
        void finishedBeforeStarted_shouldBeIgnored() {
            saga.on(new ExecutionEnqueued(1), ctx());
            assertThat(saga.on(new ExecutionFinished("?"), ctx()).isIgnored()).isTrue();
        }
    }

    // ===== Compensação =====

    @Nested
    @DisplayName("compensação e intervenção")
    class Compensating {

        @BeforeEach
        void reachRefund() {
            toInExecution();
            saga.on(new ExecutionFailed("motor danificado"), ctx());
        }

        @Test
        void refundRefusedTooManyTimes_shouldParkForIntervention() {
            SagaDecision first = saga.on(new PaymentRefundFailed("p-1", "saldo insuficiente"), ctx());
            SagaDecision second = saga.on(new PaymentRefundFailed("p-1", "saldo insuficiente"), ctx());
            SagaDecision parked = saga.on(new PaymentRefundFailed("p-1", "saldo insuficiente"), ctx());

            assertThat(first.outcome()).isEqualTo(StepOutcome.RETRIED);
            assertThat(second.outcome()).isEqualTo(StepOutcome.RETRIED);
            assertThat(parked.outcome()).isEqualTo(StepOutcome.COMPENSATION_PENDING);
            assertThat(saga.state()).isEqualTo(SagaState.COMPENSATION_PENDING);
            assertThat(saga.deadlineAt()).isNull();
            // Nunca termina CANCELLED com dinheiro retido.
            assertThat(parked.effect()).isEqualTo(OrderEffect.NONE);
        }

        @Test
        void manualRetry_shouldResumeTheCompensation() {
            parkTheRefund();

            SagaDecision retried = saga.retryCompensation(ctx());
            saga.on(new PaymentRefunded("p-1"), ctx());
            SagaDecision cancelled = saga.on(new BudgetVoided("b-1"), ctx());

            assertThat(retried.commands()).singleElement().isInstanceOf(RefundPayment.class);
            assertThat(saga.state()).isEqualTo(SagaState.CANCELLED);
            assertThat(cancelled.effect()).isEqualTo(OrderEffect.CANCEL);
        }

        @Test
        void lateRefundConfirmation_whileParked_shouldBeAccepted() {
            parkTheRefund();

            SagaDecision decision = saga.on(new PaymentRefunded("p-1"), ctx());

            assertThat(decision.commands()).singleElement().isInstanceOf(VoidBudget.class);
            assertThat(saga.state()).isEqualTo(SagaState.COMPENSATING);
        }

        @Test
        void newRefusal_whileParked_shouldNotRetryAutomatically() {
            parkTheRefund();
            assertThat(saga.on(new PaymentRefundFailed("p-1", "x"), ctx()).isIgnored()).isTrue();
        }

        @Test
        void retryCompensation_outsideParkedState_shouldBeRejected() {
            assertThatThrownBy(() -> saga.retryCompensation(ctx())).isInstanceOf(BusinessException.class);
        }

        @Test
        void compensationTimeout_shouldResendThenPark() {
            assertThat(expireStep().commands()).singleElement().isInstanceOf(RefundPayment.class);
            expireStep();
            assertThat(expireStep().outcome()).isEqualTo(StepOutcome.COMPENSATION_PENDING);
        }

        private void parkTheRefund() {
            for (int i = 0; i < 3; i++) {
                saga.on(new PaymentRefundFailed("p-1", "saldo insuficiente"), ctx());
            }
        }
    }

    // ===== Cancelamento manual (passo C) =====

    @Nested
    @DisplayName("cancelamento manual pela oficina")
    class ManualCancellation {

        @Test
        void afterQueueing_shouldUndoEverythingInReverseOrder() {
            toAwaitingExecution();

            SagaDecision cancelTask = saga.requestCancellation("cliente desistiu", ctx());
            SagaDecision refund = saga.on(new ExecutionCancelled(false), ctx());
            SagaDecision voiding = saga.on(new PaymentRefunded("p-1"), ctx());
            SagaDecision cancelled = saga.on(new BudgetVoided("b-1"), ctx());

            assertThat(cancelTask.commands()).containsExactly(new CancelExecution("cliente desistiu"));
            assertThat(refund.commands()).containsExactly(new RefundPayment("p-1", "cliente desistiu"));
            assertThat(voiding.commands()).containsExactly(new VoidBudget("b-1", "cliente desistiu"));
            assertThat(cancelled.effect()).isEqualTo(OrderEffect.CANCEL);
        }

        @Test
        void beforeTheBudgetExists_shouldCancelImmediately() {
            saga.begin(ctx());

            SagaDecision decision = saga.requestCancellation("aberta por engano", ctx());

            assertThat(decision.outcome()).isEqualTo(StepOutcome.CANCELLED);
            assertThat(decision.effect()).isEqualTo(OrderEffect.CANCEL);
        }

        @Test
        void taskAlreadyFinished_shouldParkForIntervention() {
            toAwaitingExecution();
            saga.requestCancellation("cliente desistiu", ctx());

            SagaDecision decision = saga.on(new ExecutionCancelled(true), ctx());

            assertThat(decision.outcome()).isEqualTo(StepOutcome.COMPENSATION_PENDING);
        }

        @Test
        void whileCompensating_shouldBeRejected() {
            toAwaitingApproval();
            saga.on(new BudgetRejected("b-1", "STAFF"), ctx());

            assertThatThrownBy(() -> saga.requestCancellation("de novo", ctx())).isInstanceOf(BusinessException.class);
        }

        @Test
        void afterCompletion_shouldBeRejected() {
            toInExecution();
            saga.on(new ExecutionFinished("ok"), ctx());

            assertThatThrownBy(() -> saga.requestCancellation("tarde demais", ctx()))
                .isInstanceOf(BusinessException.class);
        }
    }

    // ===== Efeitos atrasados (spec 04 §5, regra 3; cenário S11) =====

    @Nested
    @DisplayName("efeitos que chegam depois do cancelamento")
    class LateEffects {

        @Test
        void paymentApprovedAfterCancellation_shouldBeRefunded() {
            toAwaitingPayment();
            saga.requestCancellation("cliente desistiu", ctx());
            saga.on(new BudgetVoided("b-1"), ctx());
            assertThat(saga.state()).isEqualTo(SagaState.CANCELLED);

            SagaDecision decision = saga.on(new PaymentApproved("p-1"), ctx());

            assertThat(decision.outcome()).isEqualTo(StepOutcome.ORPHAN_COMPENSATED);
            assertThat(decision.commands()).singleElement().isInstanceOf(RefundPayment.class);
            assertThat(saga.state()).isEqualTo(SagaState.CANCELLED);
        }

        @Test
        void capturedPaymentApprovedAgain_shouldNotBeRefundedTwice() {
            toInExecution();
            saga.on(new ExecutionFailed("x"), ctx());
            saga.on(new PaymentRefunded("p-1"), ctx());
            saga.on(new BudgetVoided("b-1"), ctx());

            assertThat(saga.on(new PaymentApproved("p-1"), ctx()).isIgnored()).isTrue();
        }

        @Test
        void budgetGeneratedAfterCancellation_shouldBeVoided() {
            saga.begin(ctx());
            saga.requestCancellation("aberta por engano", ctx());

            SagaDecision decision = saga.on(new BudgetGenerated("b-9"), ctx());

            assertThat(decision.commands()).containsExactly(
                new VoidBudget("b-9", "orçamento gerado depois do cancelamento da OS"));
        }

        @Test
        void completedSaga_shouldIgnoreEverything() {
            toInExecution();
            saga.on(new ExecutionFinished("ok"), ctx());

            List<SagaEvent> late = List.of(new PaymentApproved("p-1"), new BudgetGenerated("b-2"),
                new ExecutionFailed("x"));

            assertThat(late).allSatisfy(event -> assertThat(saga.on(event, ctx()).isIgnored()).isTrue());
            assertThat(saga.state()).isEqualTo(SagaState.COMPLETED);
        }
    }

    // ===== Ajudantes =====

    private SagaContext ctx() {
        return new SagaContext(POLICY, now);
    }

    /** Avança o relógio até o prazo do passo atual e dispara o timeout. */
    private SagaDecision expireStep() {
        now = saga.deadlineAt();
        return saga.onTimeout(ctx());
    }

    private void toAwaitingApproval() {
        saga.begin(ctx());
        saga.on(new BudgetGenerated("b-1"), ctx());
    }

    private void toAwaitingPayment() {
        toAwaitingApproval();
        saga.on(new BudgetApproved("b-1", "CLIENT_TOKEN"), ctx());
        saga.on(new PaymentRequested("p-1"), ctx());
    }

    private void toAwaitingExecution() {
        toAwaitingPayment();
        saga.on(new PaymentApproved("p-1"), ctx());
        saga.on(new ExecutionEnqueued(1), ctx());
    }

    private void toInExecution() {
        toAwaitingExecution();
        saga.on(new ExecutionStarted("mecanico"), ctx());
    }
}
