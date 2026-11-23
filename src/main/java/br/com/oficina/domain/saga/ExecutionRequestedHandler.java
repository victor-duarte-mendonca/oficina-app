package br.com.oficina.domain.saga;

import br.com.oficina.domain.saga.SagaEvent.ExecutionEnqueueFailed;
import br.com.oficina.domain.saga.SagaEvent.ExecutionEnqueued;

/** Passo 5: pagamento confirmado, aguardando a OS entrar na fila de execução. */
final class ExecutionRequestedHandler implements SagaStateHandler {

    @Override
    public SagaDecision on(WorkOrderSaga saga, SagaEvent event, SagaContext ctx) {
        return switch (event) {
            case ExecutionEnqueued enqueued -> {
                saga.pushCompensation(Compensation.CANCEL_EXECUTION);
                // Sem prazo: o tempo de fila e de reparo é trabalho humano, acompanhado por alerta, não por timeout.
                yield saga.advance(SagaState.AWAITING_EXECUTION, null, ctx,
                    StepOutcome.ADVANCED, OrderEffect.QUEUE_FOR_EXECUTION);
            }
            case ExecutionEnqueueFailed failed -> saga.compensate("execução não enfileirada: " + failed.reason(), ctx);
            default -> SagaStateHandler.unexpected(saga, event);
        };
    }

    @Override
    public SagaDecision onTimeout(WorkOrderSaga saga, SagaContext ctx) {
        if (saga.canRetryStep(ctx.policy())) {
            return saga.retry(new SagaCommand.EnqueueExecution(), ctx.policy().stepReplyTimeout(), ctx);
        }
        return saga.compensate("Execution não respondeu ao pedido de enfileiramento", ctx);
    }
}
