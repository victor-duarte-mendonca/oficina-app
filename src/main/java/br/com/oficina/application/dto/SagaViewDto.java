package br.com.oficina.application.dto;

import br.com.oficina.domain.saga.Compensation;
import br.com.oficina.domain.saga.SagaStepRecord;
import br.com.oficina.domain.saga.WorkOrderSaga;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** Estado da Saga e a sua trilha de passos (para a oficina acompanhar e para a demonstração). */
public record SagaViewDto(UUID id,
                          Long workOrderId,
                          String state,
                          List<String> pendingCompensations,
                          String budgetId,
                          String paymentId,
                          int paymentAttempts,
                          OffsetDateTime deadlineAt,
                          String failureReason,
                          OffsetDateTime createdAt,
                          List<Step> steps) {

    public record Step(String step, UUID messageId, String outcome, String stateAfter, String detail,
                       OffsetDateTime occurredAt) {

        static Step from(SagaStepRecord record) {
            return new Step(record.step(), record.messageId(), record.outcome().name(),
                record.stateAfter().name(), record.detail(), record.occurredAt());
        }
    }

    public static SagaViewDto from(WorkOrderSaga saga, List<SagaStepRecord> steps) {
        return new SagaViewDto(
            saga.id(),
            saga.workOrderId(),
            saga.state().name(),
            saga.pendingCompensations().stream().map(Compensation::name).toList(),
            saga.budgetId(),
            saga.paymentId(),
            saga.paymentAttempts(),
            saga.deadlineAt(),
            saga.failureReason(),
            saga.createdAt(),
            steps.stream().map(Step::from).toList());
    }
}
