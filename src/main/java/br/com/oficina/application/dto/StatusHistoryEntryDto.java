package br.com.oficina.application.dto;

import br.com.oficina.domain.model.StatusChange;
import java.time.OffsetDateTime;

/** Linha do histórico de status. Para o cliente, {@code actor} é omitido (nome de funcionário não é dele). */
public record StatusHistoryEntryDto(String fromStatus,
                                    String toStatus,
                                    String toStatusLabel,
                                    String source,
                                    String reason,
                                    String actor,
                                    String correlationId,
                                    OffsetDateTime occurredAt) {

    public static StatusHistoryEntryDto forStaff(StatusChange change) {
        return new StatusHistoryEntryDto(
            change.from() == null ? null : change.from().name(),
            change.to().name(),
            WorkOrderStatusLabel.of(change.to()),
            change.source().name(),
            change.reason(),
            change.actor(),
            change.correlationId(),
            change.occurredAt());
    }

    public static StatusHistoryEntryDto forClient(StatusChange change) {
        return new StatusHistoryEntryDto(
            change.from() == null ? null : change.from().name(),
            change.to().name(),
            WorkOrderStatusLabel.of(change.to()),
            change.source().name(),
            change.reason(),
            null,
            null,
            change.occurredAt());
    }
}
