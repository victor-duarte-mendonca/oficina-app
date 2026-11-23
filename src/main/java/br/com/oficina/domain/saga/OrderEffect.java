package br.com.oficina.domain.saga;

/**
 * O que a decisão da Saga exige da OS. A Saga não manipula o agregado {@code WorkOrder} (são agregados
 * distintos, com invariantes próprias): ela diz qual transição aplicar e a aplicação a executa na
 * mesma transação.
 */
public enum OrderEffect {
    NONE,
    REVERT_TO_DIAGNOSIS,
    AWAIT_PAYMENT,
    QUEUE_FOR_EXECUTION,
    START_EXECUTION,
    COMPLETE,
    CANCEL
}
