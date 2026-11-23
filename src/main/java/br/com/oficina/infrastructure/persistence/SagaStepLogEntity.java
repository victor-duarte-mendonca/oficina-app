package br.com.oficina.infrastructure.persistence;

import br.com.oficina.domain.saga.SagaState;
import br.com.oficina.domain.saga.StepOutcome;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;

/** Tabela {@code saga_step_log} (V7): somente inserção, nunca atualizada. */
@Entity
@Table(name = "saga_step_log")
public class SagaStepLogEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "saga_id", nullable = false, updatable = false)
    private UUID sagaId;

    @Column(nullable = false, length = 40, updatable = false)
    private String step;

    @Column(name = "message_id", updatable = false)
    private UUID messageId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30, updatable = false)
    private StepOutcome outcome;

    @Enumerated(EnumType.STRING)
    @Column(name = "state_after", nullable = false, length = 30, updatable = false)
    private SagaState stateAfter;

    @Column(length = 500, updatable = false)
    private String detail;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private OffsetDateTime occurredAt;

    protected SagaStepLogEntity() {
    }

    public SagaStepLogEntity(UUID sagaId, String step, UUID messageId, StepOutcome outcome, SagaState stateAfter,
                             String detail, OffsetDateTime occurredAt) {
        this.sagaId = sagaId;
        this.step = step;
        this.messageId = messageId;
        this.outcome = outcome;
        this.stateAfter = stateAfter;
        this.detail = detail;
        this.occurredAt = occurredAt;
    }

    public UUID getSagaId() { return sagaId; }
    public String getStep() { return step; }
    public UUID getMessageId() { return messageId; }
    public StepOutcome getOutcome() { return outcome; }
    public SagaState getStateAfter() { return stateAfter; }
    public String getDetail() { return detail; }
    public OffsetDateTime getOccurredAt() { return occurredAt; }
}
