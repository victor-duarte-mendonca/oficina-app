package br.com.oficina.domain.saga;

import br.com.oficina.domain.saga.SagaEvent.ExecutionFailed;
import br.com.oficina.domain.saga.SagaEvent.ExecutionFinished;
import br.com.oficina.domain.saga.SagaEvent.ExecutionStarted;

/**
 * Passos 6 e 7: OS na fila ({@code AWAITING_EXECUTION}) ou em reparo ({@code IN_EXECUTION}). Uma falha
 * de execução estorna o pagamento e anula o orçamento; a tarefa já falhou sozinha, então cancelá-la
 * deixa de fazer parte da compensação.
 */
final class ExecutionInProgressHandler implements SagaStateHandler {

    private final boolean started;

    ExecutionInProgressHandler(boolean started) {
        this.started = started;
    }

    @Override
    public SagaDecision on(WorkOrderSaga saga, SagaEvent event, SagaContext ctx) {
        return switch (event) {
            case ExecutionStarted ignored when !started -> saga.advance(SagaState.IN_EXECUTION, null, ctx,
                StepOutcome.ADVANCED, OrderEffect.START_EXECUTION);
            case ExecutionFinished ignored when started -> saga.complete();
            case ExecutionFailed failed -> {
                saga.dropCompensation(Compensation.CANCEL_EXECUTION);
                yield saga.compensate("falha na execução: " + failed.reason(), ctx);
            }
            default -> SagaStateHandler.unexpected(saga, event);
        };
    }
}
