package br.com.oficina.application.service;

import br.com.oficina.application.dto.StatusHistoryEntryDto;
import br.com.oficina.application.ports.in.WorkOrderHistoryUseCase;
import br.com.oficina.application.ports.out.WorkOrderHistoryPort;
import br.com.oficina.application.ports.out.WorkOrderRepositoryPort;
import br.com.oficina.application.security.AuthenticatedCaller;
import br.com.oficina.application.security.OwnershipGuard;
import br.com.oficina.domain.exception.ResourceNotFoundException;
import br.com.oficina.domain.model.WorkOrder;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import jakarta.transaction.Transactional.TxType;
import java.util.List;

@ApplicationScoped
public class WorkOrderHistoryService implements WorkOrderHistoryUseCase {

    private final WorkOrderRepositoryPort workOrders;
    private final WorkOrderHistoryPort history;
    private final OwnershipGuard ownershipGuard;

    public WorkOrderHistoryService(WorkOrderRepositoryPort workOrders, WorkOrderHistoryPort history,
                                   OwnershipGuard ownershipGuard) {
        this.workOrders = workOrders;
        this.history = history;
        this.ownershipGuard = ownershipGuard;
    }

    @Override
    @Transactional(TxType.SUPPORTS)
    public List<StatusHistoryEntryDto> history(Long workOrderId) {
        WorkOrder workOrder = load(workOrderId);
        return history.findByWorkOrderId(workOrder.getId()).stream().map(StatusHistoryEntryDto::forStaff).toList();
    }

    @Override
    @Transactional(TxType.SUPPORTS)
    public List<StatusHistoryEntryDto> historyForClient(AuthenticatedCaller caller, Long workOrderId) {
        WorkOrder workOrder = load(workOrderId);
        ownershipGuard.assertOwns(caller, workOrder);
        return history.findByWorkOrderId(workOrder.getId()).stream().map(StatusHistoryEntryDto::forClient).toList();
    }

    private WorkOrder load(Long workOrderId) {
        return workOrders.fetchById(workOrderId)
            .orElseThrow(() -> new ResourceNotFoundException("Ordem de Serviço", workOrderId));
    }
}
