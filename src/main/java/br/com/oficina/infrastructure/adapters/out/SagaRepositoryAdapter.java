package br.com.oficina.infrastructure.adapters.out;

import br.com.oficina.application.ports.out.SagaRepositoryPort;
import br.com.oficina.domain.saga.Compensation;
import br.com.oficina.domain.saga.SagaState;
import br.com.oficina.domain.saga.SagaStepRecord;
import br.com.oficina.domain.saga.WorkOrderSaga;
import br.com.oficina.infrastructure.persistence.SagaInstanceEntity;
import br.com.oficina.infrastructure.persistence.SagaStepLogEntity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.EntityManager;
import jakarta.persistence.OptimisticLockException;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Persistência da Saga. A pilha de compensações é gravada como texto ordenado (topo primeiro), o que
 * mantém a tabela legível numa consulta manual durante a demonstração.
 */
@ApplicationScoped
public class SagaRepositoryAdapter implements SagaRepositoryPort {

    private static final List<SagaState> TERMINAL = Arrays.stream(SagaState.values())
        .filter(SagaState::isTerminal).toList();

    private final EntityManager em;

    public SagaRepositoryAdapter(EntityManager em) {
        this.em = em;
    }

    @Override
    public WorkOrderSaga save(WorkOrderSaga saga) {
        SagaInstanceEntity entity = em.find(SagaInstanceEntity.class, saga.id());
        if (entity == null) {
            entity = new SagaInstanceEntity(saga.id(), saga.workOrderId(), saga.createdAt());
            apply(entity, saga);
            em.persist(entity);
        } else {
            if (!Objects.equals(entity.getVersion(), saga.version())) {
                throw new OptimisticLockException("Saga " + saga.id() + " foi alterada por outro processamento");
            }
            apply(entity, saga);
        }
        em.flush();
        return toDomain(entity);
    }

    @Override
    public Optional<WorkOrderSaga> findById(UUID sagaId) {
        return Optional.ofNullable(em.find(SagaInstanceEntity.class, sagaId)).map(SagaRepositoryAdapter::toDomain);
    }

    @Override
    public Optional<WorkOrderSaga> findActiveByWorkOrderId(Long workOrderId) {
        return em.createQuery("FROM SagaInstanceEntity s WHERE s.workOrderId = :wo AND s.state NOT IN :terminal",
                SagaInstanceEntity.class)
            .setParameter("wo", workOrderId)
            .setParameter("terminal", TERMINAL)
            .getResultStream().findFirst().map(SagaRepositoryAdapter::toDomain);
    }

    @Override
    public Optional<WorkOrderSaga> findLatestByWorkOrderId(Long workOrderId) {
        return em.createQuery("FROM SagaInstanceEntity s WHERE s.workOrderId = :wo ORDER BY s.createdAt DESC",
                SagaInstanceEntity.class)
            .setParameter("wo", workOrderId)
            .setMaxResults(1)
            .getResultStream().findFirst().map(SagaRepositoryAdapter::toDomain);
    }

    @Override
    public List<UUID> findOverdueIds(OffsetDateTime now, int limit) {
        return em.createQuery("SELECT s.id FROM SagaInstanceEntity s WHERE s.deadlineAt IS NOT NULL "
                    + "AND s.deadlineAt <= :now AND s.state NOT IN :terminal ORDER BY s.deadlineAt", UUID.class)
            .setParameter("now", now)
            .setParameter("terminal", TERMINAL)
            .setMaxResults(limit)
            .getResultList();
    }

    @Override
    public void appendStep(SagaStepRecord step) {
        em.persist(new SagaStepLogEntity(step.sagaId(), step.step(), step.messageId(), step.outcome(),
            step.stateAfter(), step.detail(), step.occurredAt()));
    }

    @Override
    public List<SagaStepRecord> findSteps(UUID sagaId) {
        return em.createQuery("FROM SagaStepLogEntity l WHERE l.sagaId = :saga ORDER BY l.id", SagaStepLogEntity.class)
            .setParameter("saga", sagaId)
            .getResultStream()
            .map(l -> new SagaStepRecord(l.getSagaId(), l.getStep(), l.getMessageId(), l.getOutcome(),
                l.getStateAfter(), l.getDetail(), l.getOccurredAt()))
            .toList();
    }

    private static void apply(SagaInstanceEntity entity, WorkOrderSaga saga) {
        entity.setState(saga.state());
        entity.setCompensations(saga.pendingCompensations().stream().map(Enum::name).collect(Collectors.joining(",")));
        entity.setStepAttempts(saga.stepAttempts());
        entity.setPaymentAttempts(saga.paymentAttempts());
        entity.setBudgetId(saga.budgetId());
        entity.setPaymentId(saga.paymentId());
        entity.setPaymentCaptured(saga.paymentCaptured());
        entity.setDeadlineAt(saga.deadlineAt());
        entity.setFailureReason(truncate(saga.failureReason()));
    }

    private static WorkOrderSaga toDomain(SagaInstanceEntity e) {
        List<Compensation> compensations = e.getCompensations().isBlank()
            ? List.of()
            : Arrays.stream(e.getCompensations().split(",")).map(Compensation::valueOf).toList();
        return WorkOrderSaga.rehydrate(e.getId(), e.getWorkOrderId(), e.getState(), compensations,
            e.getStepAttempts(), e.getPaymentAttempts(), e.getBudgetId(), e.getPaymentId(), e.isPaymentCaptured(),
            e.getDeadlineAt(), e.getFailureReason(), e.getVersion(), e.getCreatedAt());
    }

    private static String truncate(String reason) {
        return reason == null || reason.length() <= 300 ? reason : reason.substring(0, 300);
    }
}
