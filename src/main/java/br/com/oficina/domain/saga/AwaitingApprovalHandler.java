package br.com.oficina.domain.saga;

import br.com.oficina.domain.saga.SagaEvent.BudgetApproved;
import br.com.oficina.domain.saga.SagaEvent.BudgetExpired;
import br.com.oficina.domain.saga.SagaEvent.BudgetRejected;

/** Passo 2: orçamento enviado, aguardando a decisão do cliente (ou do funcionário). */
final class AwaitingApprovalHandler implements SagaStateHandler {

    @Override
    public SagaDecision on(WorkOrderSaga saga, SagaEvent event, SagaContext ctx) {
        return switch (event) {
            case BudgetApproved approved -> saga.advance(SagaState.PAYMENT_REQUESTED,
                ctx.policy().stepReplyTimeout(), ctx, StepOutcome.ADVANCED, OrderEffect.NONE,
                new SagaCommand.RequestPayment(saga.budgetId(), saga.nextPaymentAttempt()));
            case BudgetRejected rejected -> saga.compensate("orçamento recusado (" + rejected.by() + ")", ctx);
            case BudgetExpired expired -> saga.compensate("orçamento expirado sem decisão", ctx);
            default -> SagaStateHandler.unexpected(saga, event);
        };
    }

    @Override
    public SagaDecision onTimeout(WorkOrderSaga saga, SagaContext ctx) {
        return saga.compensate("prazo de aprovação do orçamento esgotado", ctx);
    }
}
