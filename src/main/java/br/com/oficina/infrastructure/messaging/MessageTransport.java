package br.com.oficina.infrastructure.messaging;

import br.com.oficina.application.messaging.Destination;
import java.util.List;
import java.util.UUID;

/**
 * Transporte físico das mensagens (hoje SQS FIFO). Separado do relay e do consumidor para que ambos
 * sejam testados sem broker e para que trocar de broker (ex.: RabbitMQ, decisão D2) mexa só aqui.
 */
public interface MessageTransport {

    /**
     * @param groupId         ordem garantida dentro do grupo (uma OS)
     * @param deduplicationId mesmo id reenviado dentro da janela do broker não gera mensagem nova
     */
    void send(Destination destination, String groupId, UUID deduplicationId, String body);

    /** Próximas respostas destinadas ao OS (long polling). */
    List<ReceivedMessage> receive();

    /** Confirma o processamento: a mensagem sai da fila. Sem confirmação, volta depois do visibility timeout. */
    void acknowledge(ReceivedMessage message);

    record ReceivedMessage(String body, String receipt, int receiveCount) {
    }
}
