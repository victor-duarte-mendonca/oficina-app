package br.com.oficina.infrastructure.adapters.out;

import br.com.oficina.application.ports.out.WorkOrderHistoryPort;
import br.com.oficina.domain.model.StatusChange;
import br.com.oficina.infrastructure.observability.RequestContext;
import br.com.oficina.infrastructure.persistence.StatusHistoryEntity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.EntityManager;
import java.util.List;

/**
 * Histórico de status. Quem e por qual requisição vêm do contexto (MDC): numa transição da Saga não há
 * usuário, mas há o {@code correlationId} da mensagem que a causou — o que liga o histórico ao trace.
 */
@ApplicationScoped
public class WorkOrderHistoryAdapter implements WorkOrderHistoryPort {

    private final EntityManager em;

    public WorkOrderHistoryAdapter(EntityManager em) {
        this.em = em;
    }

    @Override
    public void record(StatusChange change) {
        String actor = change.source() == StatusChange.Source.USER ? RequestContext.username().orElse(null) : null;
        em.persist(new StatusHistoryEntity(change, actor, RequestContext.correlationId().orElse(null)));
    }

    @Override
    public List<StatusChange> findByWorkOrderId(Long workOrderId) {
        return em.createQuery("FROM StatusHistoryEntity h WHERE h.workOrderId = :wo ORDER BY h.occurredAt, h.id",
                StatusHistoryEntity.class)
            .setParameter("wo", workOrderId)
            .getResultStream()
            .map(StatusHistoryEntity::toDomain)
            .toList();
    }
}
