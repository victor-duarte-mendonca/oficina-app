package br.com.oficina.application.service;

import br.com.oficina.application.OperationMovedException;
import br.com.oficina.application.service.WorkOrderTransitions.Transition;
import br.com.oficina.domain.model.WorkOrder;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Typed;

/**
 * Fluxo da Fase 4: enviar para aprovação inicia a Saga; cancelar uma OS com Saga ativa dispara a
 * compensação (a OS só fica {@code CANCELLED} quando orçamento, pagamento e execução forem desfeitos).
 * OS sem Saga — abertas antes da Fase 4 — seguem o {@link LegacyApprovalWorkflow} até o fim.
 */
@ApplicationScoped
@Typed(SagaApprovalWorkflow.class)
public class SagaApprovalWorkflow implements ApprovalWorkflow {

    static final String MANUAL_CANCELLATION = "OS cancelada pela oficina";

    private final WorkOrderTransitions transitions;
    private final WorkOrderSagaOrchestrator orchestrator;
    private final LegacyApprovalWorkflow legacy;

    public SagaApprovalWorkflow(WorkOrderTransitions transitions, WorkOrderSagaOrchestrator orchestrator,
                                LegacyApprovalWorkflow legacy) {
        this.transitions = transitions;
        this.orchestrator = orchestrator;
        this.legacy = legacy;
    }

    @Override
    public void onDiagnosisStarted(WorkOrder workOrder) {
        orchestrator.announceDiagnosis(workOrder);
    }

    @Override
    public WorkOrder sendForApproval(WorkOrder workOrder) {
        WorkOrder saved = transitions.apply(workOrder, Transition.byUser(workOrder::submitForBudget));
        orchestrator.start(saved);
        return saved;
    }

    @Override
    public WorkOrder cancel(WorkOrder workOrder) {
        if (orchestrator.hasActiveSaga(workOrder.getId())) {
            orchestrator.requestCancellation(workOrder, MANUAL_CANCELLATION);
            return workOrder;
        }
        return legacy.cancel(workOrder);
    }

    @Override
    public void ensureDirectDecisionAllowed(WorkOrder workOrder, DirectDecision decision) {
        if (orchestrator.hasSaga(workOrder.getId())) {
            throw new OperationMovedException(decision.operation(), decision.replacement());
        }
    }
}
