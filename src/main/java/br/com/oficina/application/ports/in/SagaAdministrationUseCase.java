package br.com.oficina.application.ports.in;

import br.com.oficina.application.dto.SagaViewDto;
import java.util.List;
import java.util.UUID;

/** Operação e consulta da Saga pela oficina: acompanhar o andamento e destravar uma compensação. */
public interface SagaAdministrationUseCase {

    SagaViewDto findById(UUID sagaId);

    /** Saga mais recente da OS (ativa ou encerrada). */
    SagaViewDto findLatestForWorkOrder(Long workOrderId);

    /** Reexecuta a compensação parada em {@code COMPENSATION_PENDING} (ex.: estorno sem saldo). */
    SagaViewDto retryCompensation(UUID sagaId);

    /** Ids das Sagas com prazo vencido (o job de prazos processa uma por transação). */
    List<UUID> overdueIds(int limit);

    /** Timeout de uma Saga, na sua própria transação. */
    void expire(UUID sagaId);
}
