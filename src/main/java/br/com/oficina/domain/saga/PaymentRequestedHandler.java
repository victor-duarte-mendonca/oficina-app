package br.com.oficina.domain.saga;

import br.com.oficina.domain.saga.SagaEvent.PaymentRequestFailed;
import br.com.oficina.domain.saga.SagaEvent.PaymentRequested;

/** Passo 3: aguardando o Billing criar a cobrança Pix no Mercado Pago. */
final class PaymentRequestedHandler implements SagaStateHandler {

    @Override
    public SagaDecision on(WorkOrderSaga saga, SagaEvent event, SagaContext ctx) {
        return switch (event) {
            case PaymentRequested requested -> {
                saga.recordPayment(requested.paymentId());
                // Só a primeira cobrança move a OS; as novas tentativas já a encontram em AWAITING_PAYMENT.
                OrderEffect effect = saga.paymentAttempts() == 1 ? OrderEffect.AWAIT_PAYMENT : OrderEffect.NONE;
                yield saga.advance(SagaState.AWAITING_PAYMENT, ctx.policy().paymentTimeout(), ctx,
                    StepOutcome.ADVANCED, effect);
            }
            case PaymentRequestFailed failed -> saga.compensate("cobrança não criada: " + failed.reason(), ctx);
            default -> SagaStateHandler.unexpected(saga, event);
        };
    }

    @Override
    public SagaDecision onTimeout(WorkOrderSaga saga, SagaContext ctx) {
        if (saga.canRetryStep(ctx.policy())) {
            // Mesma tentativa = mesma chave de idempotência: o Billing devolve a cobrança já criada.
            return saga.retry(new SagaCommand.RequestPayment(saga.budgetId(), saga.paymentAttempts()),
                ctx.policy().stepReplyTimeout(), ctx);
        }
        return saga.compensate("Billing não respondeu ao pedido de pagamento", ctx);
    }
}
