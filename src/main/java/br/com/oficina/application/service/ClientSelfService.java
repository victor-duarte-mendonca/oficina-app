package br.com.oficina.application.service;

import br.com.oficina.application.Pagination;
import br.com.oficina.application.dto.ApprovalDecisionDto;
import br.com.oficina.application.dto.ClientResponseDto;
import br.com.oficina.application.dto.ClientWorkOrderRequestDto;
import br.com.oficina.application.dto.VehicleResponseDto;
import br.com.oficina.application.dto.WorkOrderCreateDto;
import br.com.oficina.application.dto.WorkOrderResponseDto;
import br.com.oficina.application.ports.in.ChangeWorkOrderStatusUseCase;
import br.com.oficina.application.ports.in.ClientSelfServiceUseCase;
import br.com.oficina.application.ports.in.CreateWorkOrderUseCase;
import br.com.oficina.application.ports.out.ClientRepositoryPort;
import br.com.oficina.application.ports.out.VehicleRepositoryPort;
import br.com.oficina.application.ports.out.WorkOrderRepositoryPort;
import br.com.oficina.application.security.AuthenticatedCaller;
import br.com.oficina.application.security.OwnershipGuard;
import br.com.oficina.domain.exception.ResourceNotFoundException;
import br.com.oficina.domain.model.Client;
import br.com.oficina.domain.model.Vehicle;
import br.com.oficina.domain.model.WorkOrder;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import jakarta.transaction.Transactional.TxType;
import java.util.List;

/**
 * Casos de uso da área do cliente. Não duplica a máquina de estados da OS: abertura e decisão de
 * orçamento delegam aos casos de uso existentes; aqui só entram a identidade vinda do token e a
 * regra de posse ({@link OwnershipGuard}).
 */
@ApplicationScoped
public class ClientSelfService implements ClientSelfServiceUseCase {

    private final ClientRepositoryPort clientRepository;
    private final VehicleRepositoryPort vehicleRepository;
    private final WorkOrderRepositoryPort workOrderRepository;
    private final OwnershipGuard ownershipGuard;
    private final CreateWorkOrderUseCase createWorkOrder;
    private final ChangeWorkOrderStatusUseCase changeStatus;

    public ClientSelfService(ClientRepositoryPort clientRepository,
                             VehicleRepositoryPort vehicleRepository,
                             WorkOrderRepositoryPort workOrderRepository,
                             OwnershipGuard ownershipGuard,
                             CreateWorkOrderUseCase createWorkOrder,
                             ChangeWorkOrderStatusUseCase changeStatus) {
        this.clientRepository = clientRepository;
        this.vehicleRepository = vehicleRepository;
        this.workOrderRepository = workOrderRepository;
        this.ownershipGuard = ownershipGuard;
        this.createWorkOrder = createWorkOrder;
        this.changeStatus = changeStatus;
    }

    @Override
    @Transactional(TxType.SUPPORTS)
    public ClientResponseDto profile(AuthenticatedCaller caller) {
        return ClientResponseDto.from(loadClient(caller));
    }

    @Override
    @Transactional(TxType.SUPPORTS)
    public List<VehicleResponseDto> vehicles(AuthenticatedCaller caller) {
        return vehicleRepository.findByClientId(caller.clientId()).stream()
            .map(VehicleResponseDto::from)
            .toList();
    }

    @Override
    @Transactional(TxType.SUPPORTS)
    public List<WorkOrderResponseDto> listWorkOrders(AuthenticatedCaller caller, int page, int size) {
        return workOrderRepository.findByClientId(caller.clientId(), Pagination.page(page), Pagination.cap(size))
            .stream()
            .map(WorkOrderResponseDto::from)
            .toList();
    }

    @Override
    @Transactional(TxType.SUPPORTS)
    public long countWorkOrders(AuthenticatedCaller caller) {
        return workOrderRepository.countByClientId(caller.clientId());
    }

    @Override
    @Transactional(TxType.SUPPORTS)
    public WorkOrderResponseDto findWorkOrder(AuthenticatedCaller caller, Long id) {
        return WorkOrderResponseDto.from(loadOwnedWorkOrder(caller, id));
    }

    @Override
    @Transactional
    public WorkOrderResponseDto openWorkOrder(AuthenticatedCaller caller, ClientWorkOrderRequestDto request) {
        Vehicle vehicle = vehicleRepository.fetchById(request.vehicleId())
            .orElseThrow(() -> new ResourceNotFoundException("Veículo", request.vehicleId()));
        ownershipGuard.assertOwns(caller, vehicle);

        Client client = loadClient(caller);
        return createWorkOrder.create(
            new WorkOrderCreateDto(client.getCpfCnpj(), vehicle.getId(), request.notes(), null, null));
    }

    @Override
    @Transactional
    public WorkOrderResponseDto decideApproval(AuthenticatedCaller caller, Long id, ApprovalDecisionDto decision) {
        WorkOrder workOrder = loadOwnedWorkOrder(caller, id);
        return switch (decision.decision()) {
            case APPROVE -> changeStatus.approve(workOrder.getId());
            case REJECT -> changeStatus.reject(workOrder.getId());
        };
    }

    private Client loadClient(AuthenticatedCaller caller) {
        return clientRepository.fetchById(caller.clientId())
            .orElseThrow(() -> new ResourceNotFoundException("Cliente", caller.clientId()));
    }

    private WorkOrder loadOwnedWorkOrder(AuthenticatedCaller caller, Long id) {
        WorkOrder workOrder = workOrderRepository.fetchById(id)
            .orElseThrow(() -> new ResourceNotFoundException("Ordem de Serviço", id));
        ownershipGuard.assertOwns(caller, workOrder);
        return workOrder;
    }
}
