package br.com.oficina.application.service;

import br.com.oficina.application.service.WorkOrderTransitions.Transition;
import br.com.oficina.domain.model.WorkOrder;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Typed;

/**
 * Fluxo da Fase 3: o OS gera o token de aprovação, notifica o cliente por e-mail e aceita a decisão e a
 * conclusão diretamente. Continua sendo o caminho das OS que já estavam em andamento antes da Saga.
 */
@ApplicationScoped
@Typed(LegacyApprovalWorkflow.class)
public class LegacyApprovalWorkflow implements ApprovalWorkflow {

    private final WorkOrderTransitions transitions;
    private final StatusNotifier notifier;
    private final WorkOrderStock stock;

    public LegacyApprovalWorkflow(WorkOrderTransitions transitions, StatusNotifier notifier, WorkOrderStock stock) {
        this.transitions = transitions;
        this.notifier = notifier;
        this.stock = stock;
    }

    @Override
    public void onDiagnosisStarted(WorkOrder workOrder) {
        // Na Fase 3 o diagnóstico é só uma transição local.
    }

    @Override
    public WorkOrder sendForApproval(WorkOrder workOrder) {
        WorkOrder saved = transitions.apply(workOrder, Transition.byUser(workOrder::sendForApproval));
        notifier.notifyClient(saved);
        return saved;
    }

    @Override
    public WorkOrder cancel(WorkOrder workOrder) {
        return transitions.apply(workOrder,
            Transition.byUser(workOrder::cancel).withSideEffects(() -> stock.restoreAll(workOrder)));
    }

    @Override
    public void ensureDirectDecisionAllowed(WorkOrder workOrder, DirectDecision decision) {
        // Sem Saga, toda decisão é do próprio OS.
    }
}
