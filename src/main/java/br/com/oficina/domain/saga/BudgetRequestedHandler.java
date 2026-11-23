package br.com.oficina.domain.saga;

import br.com.oficina.domain.saga.SagaEvent.BudgetGenerated;
import br.com.oficina.domain.saga.SagaEvent.BudgetGenerationFailed;

/** Passo 1: aguardando o Billing gerar e enviar o orçamento. */
final class BudgetRequestedHandler implements SagaStateHandler {

    @Override
    public SagaDecision on(WorkOrderSaga saga, SagaEvent event, SagaContext ctx) {
        return switch (event) {
            case BudgetGenerated generated -> {
                saga.recordBudget(generated.budgetId());
                saga.pushCompensation(Compensation.VOID_BUDGET);
                yield saga.advance(SagaState.AWAITING_APPROVAL, ctx.policy().approvalTimeout(), ctx,
                    StepOutcome.ADVANCED, OrderEffect.NONE);
            }
            case BudgetGenerationFailed failed -> saga.abort("orçamento não gerado: " + failed.reason());
            default -> SagaStateHandler.unexpected(saga, event);
        };
    }

    @Override
    public SagaDecision onTimeout(WorkOrderSaga saga, SagaContext ctx) {
        if (saga.canRetryStep(ctx.policy())) {
            return saga.retry(new SagaCommand.GenerateBudget(), ctx.policy().stepReplyTimeout(), ctx);
        }
        return saga.abort("Billing não respondeu ao pedido de orçamento");
    }
}
