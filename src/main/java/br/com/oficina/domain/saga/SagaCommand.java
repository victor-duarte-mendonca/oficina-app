package br.com.oficina.domain.saga;

/**
 * Ordens que o orquestrador envia aos participantes (spec 03 §3.1). O domínio decide <em>o quê</em>;
 * a aplicação monta o conteúdo da mensagem (itens, cliente, veículo) a partir da OS.
 */
public sealed interface SagaCommand {

    /** Participante dono do comando — define a fila de destino. */
    Participant participant();

    /** Nome do tipo no contrato de mensagens (igual ao nome do record). */
    default String type() {
        return getClass().getSimpleName();
    }

    enum Participant { BILLING, EXECUTION }

    record GenerateBudget() implements SagaCommand {
        @Override public Participant participant() { return Participant.BILLING; }
    }

    /** {@code attempt} repetido = mesma chave de idempotência no Billing (reenvio por timeout é seguro). */
    record RequestPayment(String budgetId, int attempt) implements SagaCommand {
        @Override public Participant participant() { return Participant.BILLING; }
    }

    record VoidBudget(String budgetId, String reason) implements SagaCommand {
        @Override public Participant participant() { return Participant.BILLING; }
    }

    record RefundPayment(String paymentId, String reason) implements SagaCommand {
        @Override public Participant participant() { return Participant.BILLING; }
    }

    record EnqueueExecution() implements SagaCommand {
        @Override public Participant participant() { return Participant.EXECUTION; }
    }

    record CancelExecution(String reason) implements SagaCommand {
        @Override public Participant participant() { return Participant.EXECUTION; }
    }
}
