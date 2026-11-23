package br.com.oficina.domain.saga;

/** Resultado de um passo, gravado no log da Saga (trilha de auditoria e métricas). */
public enum StepOutcome {
    STARTED,
    ADVANCED,
    RETRIED,
    COMPLETED,
    COMPENSATING,
    CANCELLED,
    /** Encerrada sem nada a desfazer fora do OS (ex.: orçamento não gerado). */
    ABORTED,
    COMPENSATION_PENDING,
    /** Pagamento ou orçamento que chegou depois do cancelamento: desfeito à parte. */
    ORPHAN_COMPENSATED,
    /** Evento sem efeito no estado atual (atrasado ou fora de ordem). */
    IGNORED
}
