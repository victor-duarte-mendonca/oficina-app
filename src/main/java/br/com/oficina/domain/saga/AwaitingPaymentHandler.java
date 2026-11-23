package br.com.oficina.domain.saga;

import br.com.oficina.domain.saga.SagaEvent.PaymentApproved;
import br.com.oficina.domain.saga.SagaEvent.PaymentExpired;
import br.com.oficina.domain.saga.SagaEvent.PaymentRejected;

/** Passo 4: Pix emitido, aguardando o pagamento. Recusa ou expiração gera nova cobrança até o limite. */
final class AwaitingPaymentHandler implements SagaStateHandler {

    @Override
    public SagaDecision on(WorkOrderSaga saga, SagaEvent event, SagaContext ctx) {
        return switch (event) {
            case PaymentApproved approved -> {
                saga.capturePayment(approved.paymentId());
                saga.pushCompensation(Compensation.REFUND_PAYMENT);
                yield saga.advance(SagaState.EXECUTION_REQUESTED, ctx.policy().stepReplyTimeout(), ctx,
                    StepOutcome.ADVANCED, OrderEffect.NONE, new SagaCommand.EnqueueExecution());
            }
            case PaymentRejected rejected when isCurrent(saga, rejected.paymentId()) ->
                retryOrCompensate(saga, ctx, "pagamento recusado: " + rejected.detail());
            case PaymentExpired expired when isCurrent(saga, expired.paymentId()) ->
                retryOrCompensate(saga, ctx, "pagamento expirado");
            default -> SagaStateHandler.unexpected(saga, event);
        };
    }

    @Override
    public SagaDecision onTimeout(WorkOrderSaga saga, SagaContext ctx) {
        return retryOrCompensate(saga, ctx, "prazo de pagamento esgotado");
    }

    /** Recusa de uma cobrança anterior à atual não conta: a tentativa vigente ainda pode ser paga. */
    private static boolean isCurrent(WorkOrderSaga saga, String paymentId) {
        return paymentId != null && paymentId.equals(saga.paymentId());
    }

    private static SagaDecision retryOrCompensate(WorkOrderSaga saga, SagaContext ctx, String reason) {
        if (!saga.hasPaymentAttemptsLeft(ctx.policy())) {
            return saga.compensate(reason + " (tentativas esgotadas)", ctx);
        }
        return saga.advance(SagaState.PAYMENT_REQUESTED, ctx.policy().stepReplyTimeout(), ctx,
                StepOutcome.RETRIED, OrderEffect.NONE,
                new SagaCommand.RequestPayment(saga.budgetId(), saga.nextPaymentAttempt()))
            .withDetail(reason);
    }
}
