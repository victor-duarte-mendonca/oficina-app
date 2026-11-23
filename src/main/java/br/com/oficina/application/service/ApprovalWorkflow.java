package br.com.oficina.application.service;

import br.com.oficina.domain.model.WorkOrder;

/**
 * Como a OS percorre orçamento, aprovação e execução (Strategy). Duas implementações convivem durante a
 * migração para microsserviços:
 * <ul>
 *   <li>{@link LegacyApprovalWorkflow} — Fase 3: o OS aprova, executa e conclui sozinho;</li>
 *   <li>{@link SagaApprovalWorkflow} — Fase 4: o OS orquestra a Saga com Billing e Execution.</li>
 * </ul>
 * A escolha é por configuração ({@code app.saga.enabled}); o {@code WorkOrderService} não sabe qual
 * está em uso. Assim a Fase 4 entra em produção só quando os outros serviços estiverem no ar, sem
 * branch de código no caso de uso.
 */
public interface ApprovalWorkflow {

    /** Diagnóstico iniciado e persistido. */
    void onDiagnosisStarted(WorkOrder workOrder);

    WorkOrder sendForApproval(WorkOrder workOrder);

    WorkOrder cancel(WorkOrder workOrder);

    /**
     * Garante que a decisão pode ser tomada diretamente no OS. No fluxo da Saga, aprovar, recusar e
     * concluir pertencem a outros serviços — exceto para OS antigas, sem Saga, que terminam pelo
     * caminho da Fase 3.
     */
    void ensureDirectDecisionAllowed(WorkOrder workOrder, DirectDecision decision);

    enum DirectDecision {
        BUDGET_DECISION("A decisão sobre o orçamento",
            "Billing Service — POST /admin/budgets/{orderNumber}/approve|reject, "
            + "POST /me/budgets/{orderNumber}/approval ou POST /public/budgets/{orderNumber}/approve|reject"),
        EXECUTION_COMPLETION("A conclusão da execução",
            "Execution Service — POST /admin/executions/{workOrderId}/finish");

        private final String operation;
        private final String replacement;

        DirectDecision(String operation, String replacement) {
            this.operation = operation;
            this.replacement = replacement;
        }

        public String operation() { return operation; }
        public String replacement() { return replacement; }
    }
}
