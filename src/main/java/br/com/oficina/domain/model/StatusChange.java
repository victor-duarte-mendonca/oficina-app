package br.com.oficina.domain.model;

import java.time.OffsetDateTime;

/**
 * Uma transição de status da OS, como aparece no histórico ("consulta de status e histórico", R1).
 * {@code actor} e {@code correlationId} vêm do contexto da requisição ou da mensagem que causou a
 * mudança — preenchidos pelo adapter, não pelo domínio.
 */
public record StatusChange(Long workOrderId,
                           WorkOrderStatus from,
                           WorkOrderStatus to,
                           Source source,
                           String reason,
                           String actor,
                           String correlationId,
                           OffsetDateTime occurredAt) {

    /** Quem provocou a mudança: um usuário pela API, a Saga por um evento, ou o sistema (ex.: backfill). */
    public enum Source { USER, SAGA, SYSTEM }

    public static StatusChange of(Long workOrderId, WorkOrderStatus from, WorkOrderStatus to,
                                  Source source, String reason, OffsetDateTime occurredAt) {
        return new StatusChange(workOrderId, from, to, source, reason, null, null, occurredAt);
    }
}
