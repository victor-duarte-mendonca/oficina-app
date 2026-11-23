package br.com.oficina.application.ports.out;

import br.com.oficina.domain.saga.SagaStepRecord;
import br.com.oficina.domain.saga.WorkOrderSaga;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistência do agregado {@link WorkOrderSaga} e da sua trilha de passos. O adapter usa trava
 * otimista: duas mensagens da mesma Saga processadas ao mesmo tempo não avançam o estado duas vezes —
 * a segunda falha, volta para a fila e é reprocessada sobre o estado novo.
 */
public interface SagaRepositoryPort {

    WorkOrderSaga save(WorkOrderSaga saga);

    Optional<WorkOrderSaga> findById(UUID sagaId);

    /** Saga não terminal da OS (no máximo uma, garantido por índice único parcial). */
    Optional<WorkOrderSaga> findActiveByWorkOrderId(Long workOrderId);

    /** Saga mais recente da OS, terminal ou não. */
    Optional<WorkOrderSaga> findLatestByWorkOrderId(Long workOrderId);

    /** Sagas com prazo vencido, da mais atrasada para a mais recente. */
    List<UUID> findOverdueIds(OffsetDateTime now, int limit);

    void appendStep(SagaStepRecord step);

    List<SagaStepRecord> findSteps(UUID sagaId);
}
