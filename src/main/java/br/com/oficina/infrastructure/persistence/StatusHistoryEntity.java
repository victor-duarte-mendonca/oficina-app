package br.com.oficina.infrastructure.persistence;

import br.com.oficina.domain.model.StatusChange;
import br.com.oficina.domain.model.WorkOrderStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;

/** Tabela {@code work_order_status_history} (V7): somente inserção. */
@Entity
@Table(name = "work_order_status_history")
public class StatusHistoryEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "work_order_id", nullable = false, updatable = false)
    private Long workOrderId;

    @Enumerated(EnumType.STRING)
    @Column(name = "from_status", length = 25, updatable = false)
    private WorkOrderStatus fromStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "to_status", nullable = false, length = 25, updatable = false)
    private WorkOrderStatus toStatus;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10, updatable = false)
    private StatusChange.Source source;

    @Column(length = 100, updatable = false)
    private String actor;

    @Column(length = 300, updatable = false)
    private String reason;

    @Column(name = "correlation_id", length = 128, updatable = false)
    private String correlationId;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private OffsetDateTime occurredAt;

    protected StatusHistoryEntity() {
    }

    public StatusHistoryEntity(StatusChange change, String actor, String correlationId) {
        this.workOrderId = change.workOrderId();
        this.fromStatus = change.from();
        this.toStatus = change.to();
        this.source = change.source();
        this.reason = truncate(change.reason(), 300);
        this.actor = truncate(actor, 100);
        this.correlationId = truncate(correlationId, 128);
        this.occurredAt = change.occurredAt();
    }

    public StatusChange toDomain() {
        return new StatusChange(workOrderId, fromStatus, toStatus, source, reason, actor, correlationId, occurredAt);
    }

    private static String truncate(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }
}
