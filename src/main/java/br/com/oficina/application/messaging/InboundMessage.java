package br.com.oficina.application.messaging;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Mensagem recebida pelo OS, já decodificada do envelope pela infraestrutura. O conteúdo fica num
 * {@link MessagePayload} para a aplicação não depender de biblioteca de JSON.
 */
public record InboundMessage(UUID messageId,
                             String type,
                             MessageKind kind,
                             UUID sagaId,
                             Long workOrderId,
                             String correlationId,
                             MessagePayload payload) {

    public InboundMessage {
        Objects.requireNonNull(messageId);
        Objects.requireNonNull(type);
        Objects.requireNonNull(kind);
        Objects.requireNonNull(payload);
    }

    public static InboundMessage of(UUID messageId, String type, MessageKind kind, UUID sagaId,
                                    Long workOrderId, Map<String, Object> payload) {
        return new InboundMessage(messageId, type, kind, sagaId, workOrderId, null, new MessagePayload(payload));
    }
}
