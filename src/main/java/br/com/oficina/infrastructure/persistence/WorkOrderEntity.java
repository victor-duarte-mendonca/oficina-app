package br.com.oficina.infrastructure.persistence;

import br.com.oficina.domain.model.WorkOrderStatus;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Entidade de persistência da OS. Mapeia a tabela {@code work_orders} e isola o
 * JPA do aggregate de domínio {@code WorkOrder}. Mantém FK para os supporting
 * domains ({@code ClientEntity}/{@code VehicleEntity}).
 */
@Entity
@Table(name = "work_orders")
public class WorkOrderEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_number", unique = true, length = 20)
    private String orderNumber;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "client_id", nullable = false)
    private ClientEntity client;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "vehicle_id", nullable = false)
    private VehicleEntity vehicle;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 25)
    private WorkOrderStatus status = WorkOrderStatus.RECEIVED;

    @Column(length = 500)
    private String notes;

    @Column(name = "total_cost", precision = 10, scale = 2)
    private BigDecimal totalCost = BigDecimal.ZERO;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @Column(name = "diagnosis_started_at")
    private OffsetDateTime diagnosisStartedAt;

    @Column(name = "sent_for_approval_at")
    private OffsetDateTime sentForApprovalAt;

    @Column(name = "approved_at")
    private OffsetDateTime approvedAt;

    @Column(name = "execution_started_at")
    private OffsetDateTime executionStartedAt;

    @Column(name = "finished_at")
    private OffsetDateTime finishedAt;

    @Column(name = "delivered_at")
    private OffsetDateTime deliveredAt;

    @Column(name = "cancelled_at")
    private OffsetDateTime cancelledAt;

    @Column(name = "approval_token", length = 64)
    private String approvalToken;

    @Column(name = "approval_token_consumed_at")
    private OffsetDateTime approvalTokenConsumedAt;

    @OneToMany(mappedBy = "workOrder", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private final List<WorkOrderPartEntity> parts = new ArrayList<>();

    @OneToMany(mappedBy = "workOrder", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private final List<WorkOrderServiceItemEntity> services = new ArrayList<>();

    public WorkOrderEntity() {
    }

    @PrePersist
    void prePersist() {
        createdAt = OffsetDateTime.now();
        updatedAt = OffsetDateTime.now();
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = OffsetDateTime.now();
    }

    public Long getId() { return id; }

    public String getOrderNumber() { return orderNumber; }
    public void setOrderNumber(String orderNumber) { this.orderNumber = orderNumber; }

    public ClientEntity getClient() { return client; }
    public void setClient(ClientEntity client) { this.client = client; }

    public VehicleEntity getVehicle() { return vehicle; }
    public void setVehicle(VehicleEntity vehicle) { this.vehicle = vehicle; }

    public WorkOrderStatus getStatus() { return status; }
    public void setStatus(WorkOrderStatus status) { this.status = status; }

    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }

    public BigDecimal getTotalCost() { return totalCost; }
    public void setTotalCost(BigDecimal totalCost) { this.totalCost = totalCost; }

    public OffsetDateTime getCreatedAt() { return createdAt; }

    public OffsetDateTime getDiagnosisStartedAt() { return diagnosisStartedAt; }
    public void setDiagnosisStartedAt(OffsetDateTime v) { this.diagnosisStartedAt = v; }

    public OffsetDateTime getSentForApprovalAt() { return sentForApprovalAt; }
    public void setSentForApprovalAt(OffsetDateTime v) { this.sentForApprovalAt = v; }

    public OffsetDateTime getApprovedAt() { return approvedAt; }
    public void setApprovedAt(OffsetDateTime v) { this.approvedAt = v; }

    public OffsetDateTime getExecutionStartedAt() { return executionStartedAt; }
    public void setExecutionStartedAt(OffsetDateTime v) { this.executionStartedAt = v; }

    public OffsetDateTime getFinishedAt() { return finishedAt; }
    public void setFinishedAt(OffsetDateTime v) { this.finishedAt = v; }

    public OffsetDateTime getDeliveredAt() { return deliveredAt; }
    public void setDeliveredAt(OffsetDateTime v) { this.deliveredAt = v; }

    public OffsetDateTime getCancelledAt() { return cancelledAt; }
    public void setCancelledAt(OffsetDateTime v) { this.cancelledAt = v; }

    public String getApprovalToken() { return approvalToken; }
    public void setApprovalToken(String v) { this.approvalToken = v; }

    public OffsetDateTime getApprovalTokenConsumedAt() { return approvalTokenConsumedAt; }
    public void setApprovalTokenConsumedAt(OffsetDateTime v) { this.approvalTokenConsumedAt = v; }

    public List<WorkOrderPartEntity> getParts() { return parts; }
    public List<WorkOrderServiceItemEntity> getServices() { return services; }
}
