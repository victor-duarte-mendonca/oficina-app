package br.com.oficina.application.ports.out;

import java.util.UUID;

/**
 * Consumidor idempotente (Inbox): o SQS entrega pelo menos uma vez, então a mesma mensagem pode chegar
 * de novo. O registro é feito na transação do processamento — se ela desfaz, a mensagem volta a ser
 * "nova" e será reprocessada.
 */
public interface InboxPort {

    /** @return {@code true} se é a primeira vez que este consumidor vê a mensagem; {@code false} se é repetida */
    boolean markProcessed(UUID messageId, String consumer);
}
