package br.com.oficina.application.service;

import br.com.oficina.application.dto.SagaViewDto;
import br.com.oficina.application.ports.in.SagaAdministrationUseCase;
import br.com.oficina.application.ports.out.SagaRepositoryPort;
import br.com.oficina.domain.exception.ResourceNotFoundException;
import br.com.oficina.domain.saga.WorkOrderSaga;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import jakarta.transaction.Transactional.TxType;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** Consulta e operação da Saga pela oficina. A execução de qualquer passo é delegada ao orquestrador. */
@ApplicationScoped
public class SagaAdministrationService implements SagaAdministrationUseCase {

    private final SagaRepositoryPort sagas;
    private final WorkOrderSagaOrchestrator orchestrator;
    private final Clock clock;

    public SagaAdministrationService(SagaRepositoryPort sagas, WorkOrderSagaOrchestrator orchestrator, Clock clock) {
        this.sagas = sagas;
        this.orchestrator = orchestrator;
        this.clock = clock;
    }

    @Override
    @Transactional(TxType.SUPPORTS)
    public SagaViewDto findById(UUID sagaId) {
        return view(sagas.findById(sagaId)
            .orElseThrow(() -> new ResourceNotFoundException("Saga não encontrada: " + sagaId)));
    }

    @Override
    @Transactional(TxType.SUPPORTS)
    public SagaViewDto findLatestForWorkOrder(Long workOrderId) {
        return view(sagas.findLatestByWorkOrderId(workOrderId)
            .orElseThrow(() -> new ResourceNotFoundException("A OS " + workOrderId + " não tem Saga")));
    }

    @Override
    public SagaViewDto retryCompensation(UUID sagaId) {
        return view(orchestrator.retryCompensation(sagaId));
    }

    @Override
    @Transactional(TxType.SUPPORTS)
    public List<UUID> overdueIds(int limit) {
        return sagas.findOverdueIds(OffsetDateTime.now(clock), limit);
    }

    @Override
    public void expire(UUID sagaId) {
        orchestrator.expire(sagaId);
    }

    private SagaViewDto view(WorkOrderSaga saga) {
        return SagaViewDto.from(saga, sagas.findSteps(saga.id()));
    }
}
