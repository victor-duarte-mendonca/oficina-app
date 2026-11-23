package br.com.oficina.infrastructure.persistence;

import br.com.oficina.domain.saga.SagaState;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.OffsetDateTime;
import java.util.UUID;

/** Tabela {@code saga_instance} (V7). {@code @Version} é a trava otimista entre consumidores concorrentes. */
@Entity
@Table(name = "saga_instance")
public class SagaInstanceEntity {

    @Id
    private UUID id;

    @Column(name = "work_order_id", nullable = false, updatable = false)
    private Long workOrderId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private SagaState state;

    @Column(nullable = false, length = 100)
    private String compensations = "";

    @Column(name = "step_attempts", nullable = false)
    private int stepAttempts;

    @Column(name = "payment_attempts", nullable = false)
    private int paymentAttempts;

    @Column(name = "budget_id", length = 64)
    private String budgetId;

    @Column(name = "payment_id", length = 64)
    private String paymentId;

    @Column(name = "payment_captured", nullable = false)
    private boolean paymentCaptured;

    @Column(name = "deadline_at")
    private OffsetDateTime deadlineAt;

    @Column(name = "failure_reason", length = 300)
    private String failureReason;

    @Version
    private Long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    protected SagaInstanceEntity() {
    }

    public SagaInstanceEntity(UUID id, Long workOrderId, OffsetDateTime createdAt) {
        this.id = id;
        this.workOrderId = workOrderId;
        this.createdAt = createdAt;
    }

    @PrePersist
    void prePersist() {
        if (createdAt == null) {
            createdAt = OffsetDateTime.now();
        }
        updatedAt = OffsetDateTime.now();
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = OffsetDateTime.now();
    }

    public UUID getId() { return id; }
    public Long getWorkOrderId() { return workOrderId; }
    public SagaState getState() { return state; }
    public void setState(SagaState state) { this.state = state; }
    public String getCompensations() { return compensations; }
    public void setCompensations(String compensations) { this.compensations = compensations; }
    public int getStepAttempts() { return stepAttempts; }
    public void setStepAttempts(int stepAttempts) { this.stepAttempts = stepAttempts; }
    public int getPaymentAttempts() { return paymentAttempts; }
    public void setPaymentAttempts(int paymentAttempts) { this.paymentAttempts = paymentAttempts; }
    public String getBudgetId() { return budgetId; }
    public void setBudgetId(String budgetId) { this.budgetId = budgetId; }
    public String getPaymentId() { return paymentId; }
    public void setPaymentId(String paymentId) { this.paymentId = paymentId; }
    public boolean isPaymentCaptured() { return paymentCaptured; }
    public void setPaymentCaptured(boolean paymentCaptured) { this.paymentCaptured = paymentCaptured; }
    public OffsetDateTime getDeadlineAt() { return deadlineAt; }
    public void setDeadlineAt(OffsetDateTime deadlineAt) { this.deadlineAt = deadlineAt; }
    public String getFailureReason() { return failureReason; }
    public void setFailureReason(String failureReason) { this.failureReason = failureReason; }
    public Long getVersion() { return version; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
}
