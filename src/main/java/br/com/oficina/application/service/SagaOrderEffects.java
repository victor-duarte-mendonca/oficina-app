package br.com.oficina.application.service;

import br.com.oficina.application.service.WorkOrderTransitions.Transition;
import br.com.oficina.domain.model.WorkOrder;
import br.com.oficina.domain.saga.OrderEffect;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Aplica na OS a transição que a Saga decidiu. Cada efeito vira uma transição do agregado
 * {@code WorkOrder}, registrada no histórico com origem {@code SAGA}; o cancelamento devolve o estoque
 * (compensação local) e a conclusão notifica o cliente, como no fluxo da Fase 3.
 */
@ApplicationScoped
public class SagaOrderEffects {

    private final WorkOrderTransitions transitions;
    private final WorkOrderStock stock;
    private final StatusNotifier notifier;

    public SagaOrderEffects(WorkOrderTransitions transitions, WorkOrderStock stock, StatusNotifier notifier) {
        this.transitions = transitions;
        this.stock = stock;
        this.notifier = notifier;
    }

    public WorkOrder apply(OrderEffect effect, WorkOrder workOrder, String detail) {
        return switch (effect) {
            case NONE -> workOrder;
            case REVERT_TO_DIAGNOSIS -> bySaga(workOrder, workOrder::revertToDiagnosis,
                reason(detail, "orçamento não gerado"));
            case AWAIT_PAYMENT -> bySaga(workOrder, workOrder::awaitPayment, reason(detail, "orçamento aprovado; Pix emitido"));
            case QUEUE_FOR_EXECUTION -> bySaga(workOrder, workOrder::queueForExecution,
                reason(detail, "pagamento confirmado; OS na fila de execução"));
            case START_EXECUTION -> bySaga(workOrder, workOrder::startExecution, reason(detail, "execução iniciada"));
            case COMPLETE -> {
                WorkOrder finished = bySaga(workOrder, workOrder::complete, reason(detail, "execução concluída"));
                notifier.notifyClient(finished);
                yield finished;
            }
            case CANCEL -> transitions.apply(workOrder,
                Transition.bySaga(workOrder::cancel, reason(detail, "Saga compensada"))
                    .withSideEffects(() -> stock.restoreAll(workOrder)));
        };
    }

    private WorkOrder bySaga(WorkOrder workOrder, Runnable change, String reason) {
        return transitions.apply(workOrder, Transition.bySaga(change, reason));
    }

    private static String reason(String detail, String fallback) {
        return detail == null || detail.isBlank() ? fallback : detail;
    }
}
