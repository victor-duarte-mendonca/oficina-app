package br.com.oficina.application.messaging;

import java.time.OffsetDateTime;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Mensagem que o OS publica (via outbox). Campos de contexto técnico do envelope ({@code correlationId},
 * {@code traceparent}) são acrescentados pela infraestrutura no momento do enfileiramento.
 *
 * @param causationId mensagem que provocou esta; {@code null} quando a origem é uma requisição HTTP
 */
public record OutboundMessage(UUID messageId,
                              Destination destination,
                              String type,
                              MessageKind kind,
                              UUID sagaId,
                              Long workOrderId,
                              String orderNumber,
                              UUID causationId,
                              OffsetDateTime occurredAt,
                              Map<String, Object> payload) {

    /** Versão do contrato de todas as mensagens publicadas hoje (evolução aditiva; spec 03 §2). */
    public static final int CONTRACT_VERSION = 1;

    public OutboundMessage {
        Objects.requireNonNull(messageId);
        Objects.requireNonNull(destination);
        Objects.requireNonNull(type);
        Objects.requireNonNull(kind);
        Objects.requireNonNull(workOrderId);
        Objects.requireNonNull(occurredAt);
        // Cópia que aceita null (e-mail ausente é um valor válido do contrato), exposta como somente leitura.
        payload = Collections.unmodifiableMap(new LinkedHashMap<>(payload));
    }

    /** Ordem garantida por OS: o SQS FIFO serializa as mensagens de um mesmo grupo. */
    public String groupId() {
        return "wo-" + workOrderId;
    }
}
