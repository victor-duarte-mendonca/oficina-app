package br.com.oficina.application.messaging;

import br.com.oficina.domain.saga.SagaCommand.Participant;

/**
 * Destino lógico de uma mensagem publicada pelo OS. A fila física (URL do SQS) é resolvida pela
 * infraestrutura a partir da configuração — a aplicação não conhece filas.
 */
public enum Destination {
    BILLING_COMMANDS,
    EXECUTION_COMMANDS;

    public static Destination of(Participant participant) {
        return switch (participant) {
            case BILLING -> BILLING_COMMANDS;
            case EXECUTION -> EXECUTION_COMMANDS;
        };
    }
}
