package br.com.oficina.domain.saga;

/**
 * Fatos que os participantes reportam ao orquestrador (catálogo da spec 03 §3.2). Tipos fechados
 * ({@code sealed}): um evento novo obriga cada handler a decidir explicitamente o que fazer com ele.
 *
 * <p>Os eventos carregam só identificadores e motivos — nunca dados pessoais.</p>
 */
public sealed interface SagaEvent {

    /** Nome do tipo no contrato de mensagens (igual ao nome do record). */
    default String type() {
        return getClass().getSimpleName();
    }

    // ===== Billing =====
    record BudgetGenerated(String budgetId) implements SagaEvent { }
    record BudgetGenerationFailed(String reason) implements SagaEvent { }
    record BudgetApproved(String budgetId, String by) implements SagaEvent { }
    record BudgetRejected(String budgetId, String by) implements SagaEvent { }
    record BudgetExpired(String budgetId) implements SagaEvent { }
    record BudgetVoided(String budgetId) implements SagaEvent { }
    record PaymentRequested(String paymentId) implements SagaEvent { }
    record PaymentRequestFailed(String reason) implements SagaEvent { }
    record PaymentApproved(String paymentId) implements SagaEvent { }
    record PaymentRejected(String paymentId, String detail) implements SagaEvent { }
    record PaymentExpired(String paymentId) implements SagaEvent { }
    record PaymentRefunded(String paymentId) implements SagaEvent { }
    record PaymentRefundFailed(String paymentId, String detail) implements SagaEvent { }

    // ===== Execution =====
    record ExecutionEnqueued(int position) implements SagaEvent { }
    record ExecutionEnqueueFailed(String reason) implements SagaEvent { }
    record ExecutionStarted(String assignee) implements SagaEvent { }
    record ExecutionFinished(String summary) implements SagaEvent { }
    record ExecutionFailed(String reason) implements SagaEvent { }
    record ExecutionCancelled(boolean alreadyFinished) implements SagaEvent { }
}
