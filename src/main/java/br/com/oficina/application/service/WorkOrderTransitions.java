package br.com.oficina.application.service;

import br.com.oficina.application.ports.out.BusinessMetricsPort;
import br.com.oficina.application.ports.out.BusinessMetricsPort.TransitionFailure;
import br.com.oficina.application.ports.out.WorkOrderHistoryPort;
import br.com.oficina.application.ports.out.WorkOrderRepositoryPort;
import br.com.oficina.domain.exception.InvalidStatusTransitionException;
import br.com.oficina.domain.model.StatusChange;
import br.com.oficina.domain.model.StatusChange.Source;
import br.com.oficina.domain.model.WorkOrder;
import br.com.oficina.domain.model.WorkOrderStatus;
import jakarta.enterprise.context.ApplicationScoped;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.Optional;

/**
 * Executa uma transição de status da OS do mesmo jeito, venha ela de um usuário (API) ou da Saga
 * (evento): transição no agregado, efeitos colaterais na mesma transação, persistência, histórico e
 * métricas do funil. Um único lugar garante que nenhuma transição escapa do histórico.
 */
@ApplicationScoped
public class WorkOrderTransitions {

    private final WorkOrderRepositoryPort repository;
    private final BusinessMetricsPort metrics;
    private final WorkOrderHistoryPort history;

    public WorkOrderTransitions(WorkOrderRepositoryPort repository, BusinessMetricsPort metrics,
                                WorkOrderHistoryPort history) {
        this.repository = repository;
        this.metrics = metrics;
        this.history = history;
    }

    public WorkOrder apply(WorkOrder workOrder, Transition transition) {
        WorkOrderStatus from = workOrder.getStatus();
        Optional<OffsetDateTime> enteredFrom = workOrder.statusEnteredAt();
        try {
            transition.change().run();
        } catch (InvalidStatusTransitionException e) {
            metrics.transitionFailed(TransitionFailure.INVALID_TRANSITION);
            throw e;
        }
        transition.sideEffects().run();
        WorkOrder saved = persist(workOrder);
        OffsetDateTime now = OffsetDateTime.now();
        history.record(StatusChange.of(saved.getId(), from, saved.getStatus(), transition.source(),
            transition.reason(), now));
        metrics.statusTransition(from, saved.getStatus(), enteredFrom.map(entered -> Duration.between(entered, now)));
        return saved;
    }

    /** Persiste sem transição de status, contando a falha de banco como falha de processamento de OS. */
    public WorkOrder persist(WorkOrder workOrder) {
        try {
            return repository.save(workOrder);
        } catch (RuntimeException e) {
            metrics.transitionFailed(TransitionFailure.PERSISTENCE);
            throw e;
        }
    }

    /** Primeira linha do histórico: a abertura da OS. */
    public void recordOpening(WorkOrder created) {
        history.record(StatusChange.of(created.getId(), null, created.getStatus(), Source.USER,
            "OS aberta", OffsetDateTime.now()));
    }

    /**
     * Transição a aplicar: a mudança no agregado, efeitos na mesma transação (ex.: devolver estoque) e
     * a origem para o histórico.
     */
    public record Transition(Runnable change, Runnable sideEffects, Source source, String reason) {

        private static final Runnable NONE = () -> { };

        public Transition {
            Objects.requireNonNull(change);
            Objects.requireNonNull(sideEffects);
            Objects.requireNonNull(source);
        }

        public static Transition byUser(Runnable change) {
            return new Transition(change, NONE, Source.USER, null);
        }

        public static Transition bySaga(Runnable change, String reason) {
            return new Transition(change, NONE, Source.SAGA, reason);
        }

        public Transition withSideEffects(Runnable effects) {
            return new Transition(change, effects, source, reason);
        }
    }
}
