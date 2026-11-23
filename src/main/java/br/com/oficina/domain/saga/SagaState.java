package br.com.oficina.domain.saga;

/**
 * Estados da Saga da OS (spec 04 §3). Os estados de "pedido" ({@code *_REQUESTED}) aguardam a resposta
 * técnica de um comando; os de "espera" ({@code AWAITING_*}) aguardam um fato de negócio externo.
 *
 * <p>{@code COMPENSATION_PENDING} não é terminal: há dinheiro ou trabalho a desfazer e a Saga só sai
 * dele por nova tentativa (manual ou por um evento atrasado de sucesso).</p>
 */
public enum SagaState {

    BUDGET_REQUESTED,
    AWAITING_APPROVAL,
    PAYMENT_REQUESTED,
    AWAITING_PAYMENT,
    EXECUTION_REQUESTED,
    AWAITING_EXECUTION,
    IN_EXECUTION,
    COMPENSATING,
    COMPENSATION_PENDING,
    COMPLETED(true),
    CANCELLED(true);

    private final boolean terminal;

    SagaState() {
        this(false);
    }

    SagaState(boolean terminal) {
        this.terminal = terminal;
    }

    public boolean isTerminal() {
        return terminal;
    }

    /** Em compensação (automática ou aguardando intervenção): não aceita novo cancelamento. */
    public boolean isCompensating() {
        return this == COMPENSATING || this == COMPENSATION_PENDING;
    }
}
