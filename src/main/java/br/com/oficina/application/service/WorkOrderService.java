package br.com.oficina.application.service;

import br.com.oficina.application.dto.*;
import br.com.oficina.application.ports.in.ApproveBudgetUseCase;
import br.com.oficina.application.ports.in.ChangeWorkOrderStatusUseCase;
import br.com.oficina.application.ports.in.CreateWorkOrderUseCase;
import br.com.oficina.application.ports.in.ListWorkOrdersUseCase;
import br.com.oficina.application.ports.in.ManageWorkOrderItemsUseCase;
import br.com.oficina.application.Pagination;
import br.com.oficina.application.service.ApprovalWorkflow.DirectDecision;
import br.com.oficina.application.service.WorkOrderTransitions.Transition;
import br.com.oficina.domain.exception.BusinessException;
import br.com.oficina.domain.exception.ResourceNotFoundException;
import br.com.oficina.domain.model.*;
import br.com.oficina.application.ports.out.BusinessMetricsPort;
import br.com.oficina.application.ports.out.BusinessMetricsPort.TransitionFailure;
import br.com.oficina.application.ports.out.ClientRepositoryPort;
import br.com.oficina.application.ports.out.PartRepositoryPort;
import br.com.oficina.application.ports.out.ServiceItemRepositoryPort;
import br.com.oficina.application.ports.out.VehicleRepositoryPort;
import br.com.oficina.application.ports.out.WorkOrderRepositoryPort;
import br.com.oficina.domain.valueobject.CpfCnpj;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import jakarta.transaction.Transactional.TxType;
import java.util.List;

/**
 * Casos de uso da OS. As transições passam por {@link WorkOrderTransitions} (histórico e métricas num
 * só lugar); orçamento, aprovação, conclusão e cancelamento seguem o {@link ApprovalWorkflow} vigente
 * — o da Fase 3 ou o da Saga — sem que este serviço precise saber qual.
 */
@ApplicationScoped
public class WorkOrderService implements CreateWorkOrderUseCase, ManageWorkOrderItemsUseCase,
        ChangeWorkOrderStatusUseCase, ApproveBudgetUseCase, ListWorkOrdersUseCase {

    private final WorkOrderRepositoryPort workOrderRepository;
    private final ClientRepositoryPort clientRepository;
    private final VehicleRepositoryPort vehicleRepository;
    private final PartRepositoryPort partRepository;
    private final ServiceItemRepositoryPort serviceItemRepository;
    private final BusinessMetricsPort metrics;
    private final WorkOrderTransitions transitions;
    private final WorkOrderStock stock;
    private final StatusNotifier notifier;
    private final ApprovalWorkflow workflow;

    public WorkOrderService(WorkOrderRepositoryPort workOrderRepository,
                            ClientRepositoryPort clientRepository,
                            VehicleRepositoryPort vehicleRepository,
                            PartRepositoryPort partRepository,
                            ServiceItemRepositoryPort serviceItemRepository,
                            BusinessMetricsPort metrics,
                            WorkOrderTransitions transitions,
                            WorkOrderStock stock,
                            StatusNotifier notifier,
                            ApprovalWorkflow workflow) {
        this.workOrderRepository = workOrderRepository;
        this.clientRepository = clientRepository;
        this.vehicleRepository = vehicleRepository;
        this.partRepository = partRepository;
        this.serviceItemRepository = serviceItemRepository;
        this.metrics = metrics;
        this.transitions = transitions;
        this.stock = stock;
        this.notifier = notifier;
        this.workflow = workflow;
    }

    @Override
    @Transactional(TxType.SUPPORTS)
    public List<WorkOrderResponseDto> listActive(int page, int size) {
        return workOrderRepository.findActive(Pagination.page(page), Pagination.cap(size)).stream()
            .map(WorkOrderResponseDto::from)
            .toList();
    }

    @Override
    @Transactional(TxType.SUPPORTS)
    public List<WorkOrderResponseDto> listByStatus(WorkOrderStatus status, int page, int size) {
        return workOrderRepository.findByStatus(status, Pagination.page(page), Pagination.cap(size)).stream()
            .map(WorkOrderResponseDto::from)
            .toList();
    }

    @Override
    @Transactional(TxType.SUPPORTS)
    public long countActive() {
        return workOrderRepository.countActive();
    }

    @Override
    @Transactional(TxType.SUPPORTS)
    public long countByStatus(WorkOrderStatus status) {
        return workOrderRepository.countByStatus(status);
    }

    @Override
    @Transactional(TxType.SUPPORTS)
    public WorkOrderResponseDto findById(Long id) {
        return WorkOrderResponseDto.from(findWorkOrder(id));
    }

    @Override
    @Transactional(TxType.SUPPORTS)
    public WorkOrderResponseDto findByOrderNumber(String orderNumber) {
        WorkOrder wo = workOrderRepository.findByOrderNumber(orderNumber)
            .orElseThrow(() -> new ResourceNotFoundException(
                "Ordem de Serviço não encontrada: " + orderNumber
            ));
        return WorkOrderResponseDto.from(wo);
    }

    @Override
    @Transactional
    public WorkOrderResponseDto create(WorkOrderCreateDto dto, Origin origin) {
        String normalizedCpfCnpj = CpfCnpj.normalize(dto.clientCpfCnpj());
        Client client = clientRepository.findByCpfCnpj(normalizedCpfCnpj)
            .orElseThrow(() -> new ResourceNotFoundException(
                "Cliente não encontrado com CPF/CNPJ: " + dto.clientCpfCnpj()
            ));

        Vehicle vehicle = vehicleRepository.fetchById(dto.vehicleId())
            .orElseThrow(() -> new ResourceNotFoundException("Veículo", dto.vehicleId()));

        if (!vehicle.getClientId().equals(client.getId())) {
            throw new BusinessException("O veículo não pertence ao cliente informado");
        }

        WorkOrder wo = new WorkOrder(snapshotOf(client), snapshotOf(vehicle), dto.notes());
        wo = transitions.persist(wo);

        if (dto.services() != null) {
            for (WorkOrderServiceDto s : dto.services()) {
                addServiceToOrder(wo, s);
            }
        }
        if (dto.parts() != null) {
            for (WorkOrderPartDto p : dto.parts()) {
                addPartToOrder(wo, p);
            }
        }
        WorkOrder saved = transitions.persist(wo);
        transitions.recordOpening(saved);
        metrics.workOrderCreated(origin);
        return WorkOrderResponseDto.from(saved);
    }

    @Override
    @Transactional
    public WorkOrderResponseDto addService(Long id, WorkOrderServiceDto dto) {
        WorkOrder wo = findWorkOrder(id);
        addServiceToOrder(wo, dto);
        return WorkOrderResponseDto.from(workOrderRepository.save(wo));
    }

    @Override
    @Transactional
    public WorkOrderResponseDto addPart(Long id, WorkOrderPartDto dto) {
        WorkOrder wo = findWorkOrder(id);
        addPartToOrder(wo, dto);
        return WorkOrderResponseDto.from(workOrderRepository.save(wo));
    }

    @Override
    @Transactional
    public WorkOrderResponseDto removeService(Long id, Long serviceLineId) {
        WorkOrder wo = findWorkOrder(id);
        wo.removeService(serviceLineId);
        return WorkOrderResponseDto.from(workOrderRepository.save(wo));
    }

    @Override
    @Transactional
    public WorkOrderResponseDto removePart(Long id, Long partLineId) {
        WorkOrder wo = findWorkOrder(id);
        WorkOrderPart removed = wo.removePart(partLineId);
        stock.restore(removed);
        return WorkOrderResponseDto.from(workOrderRepository.save(wo));
    }

    @Override
    @Transactional
    public WorkOrderResponseDto startDiagnosis(Long id) {
        WorkOrder wo = findWorkOrder(id);
        WorkOrder saved = transitions.apply(wo, Transition.byUser(wo::startDiagnosis));
        workflow.onDiagnosisStarted(saved);
        return WorkOrderResponseDto.from(saved);
    }

    @Override
    @Transactional
    public WorkOrderResponseDto sendForApproval(Long id) {
        return WorkOrderResponseDto.from(workflow.sendForApproval(findWorkOrder(id)));
    }

    @Override
    @Transactional
    public WorkOrderResponseDto approve(Long id) {
        WorkOrder wo = findWorkOrder(id);
        workflow.ensureDirectDecisionAllowed(wo, DirectDecision.BUDGET_DECISION);
        return WorkOrderResponseDto.from(transitions.apply(wo, Transition.byUser(wo::approve)));
    }

    @Override
    @Transactional
    public WorkOrderResponseDto reject(Long id) {
        WorkOrder wo = findWorkOrder(id);
        workflow.ensureDirectDecisionAllowed(wo, DirectDecision.BUDGET_DECISION);
        return WorkOrderResponseDto.from(transitions.apply(wo, rejection(wo, wo::reject)));
    }

    @Override
    @Transactional
    public WorkOrderResponseDto complete(Long id) {
        WorkOrder wo = findWorkOrder(id);
        workflow.ensureDirectDecisionAllowed(wo, DirectDecision.EXECUTION_COMPLETION);
        WorkOrder saved = transitions.apply(wo, Transition.byUser(wo::complete));
        notifier.notifyClient(saved);
        return WorkOrderResponseDto.from(saved);
    }

    @Override
    @Transactional
    public WorkOrderResponseDto deliver(Long id) {
        WorkOrder wo = findWorkOrder(id);
        WorkOrder saved = transitions.apply(wo, Transition.byUser(wo::deliver));
        notifier.notifyClient(saved);
        return WorkOrderResponseDto.from(saved);
    }

    @Override
    @Transactional
    public WorkOrderResponseDto cancel(Long id) {
        return WorkOrderResponseDto.from(workflow.cancel(findWorkOrder(id)));
    }

    @Override
    @Transactional
    public WorkOrderResponseDto approveByOrderNumber(String orderNumber, String clientCpfCnpj,
                                                     String approvalToken) {
        WorkOrder wo = findAndAuthorize(orderNumber, clientCpfCnpj);
        workflow.ensureDirectDecisionAllowed(wo, DirectDecision.BUDGET_DECISION);
        return WorkOrderResponseDto.from(
            transitions.apply(wo, Transition.byUser(() -> wo.approveWithToken(approvalToken))));
    }

    @Override
    @Transactional
    public WorkOrderResponseDto rejectByOrderNumber(String orderNumber, String clientCpfCnpj,
                                                    String approvalToken) {
        WorkOrder wo = findAndAuthorize(orderNumber, clientCpfCnpj);
        workflow.ensureDirectDecisionAllowed(wo, DirectDecision.BUDGET_DECISION);
        return WorkOrderResponseDto.from(
            transitions.apply(wo, rejection(wo, () -> wo.rejectWithToken(approvalToken))));
    }

    /** Recusa do orçamento: a OS é cancelada e as peças reservadas voltam ao estoque. */
    private Transition rejection(WorkOrder wo, Runnable change) {
        return Transition.byUser(change).withSideEffects(() -> stock.restoreAll(wo));
    }

    /**
     * Primeira barreira do canal público: localiza a OS e confere o CPF/CNPJ. A
     * segunda barreira — o código de uso único enviado por e-mail — é validada
     * dentro do aggregate, junto da transição de estado, para que não exista
     * caminho de aprovação que a contorne.
     */
    private WorkOrder findAndAuthorize(String orderNumber, String providedCpfCnpj) {
        WorkOrder wo = workOrderRepository.findByOrderNumber(orderNumber)
            .orElseThrow(() -> new ResourceNotFoundException(
                "Ordem de Serviço não encontrada: " + orderNumber));

        String normalized = CpfCnpj.normalize(providedCpfCnpj);
        if (!wo.getCustomer().cpfCnpj().equals(normalized)) {
            // Mensagem genérica para não distinguir "OS inexistente" de "CPF/CNPJ não confere"
            throw new ResourceNotFoundException("Ordem de Serviço não encontrada: " + orderNumber);
        }
        return wo;
    }

    private WorkOrder findWorkOrder(Long id) {
        return workOrderRepository.fetchById(id)
            .orElseThrow(() -> new ResourceNotFoundException("Ordem de Serviço", id));
    }

    private void addServiceToOrder(WorkOrder wo, WorkOrderServiceDto dto) {
        ServiceItem serviceItem = serviceItemRepository.fetchById(dto.serviceItemId())
            .orElseThrow(() -> new ResourceNotFoundException("Serviço", dto.serviceItemId()));
        if (!serviceItem.isActive()) {
            throw new BusinessException("Serviço inativo: " + serviceItem.getName());
        }
        wo.addService(serviceItem.getId(), serviceItem.getName(), serviceItem.getBasePrice(), dto.notes());
    }

    private void addPartToOrder(WorkOrder wo, WorkOrderPartDto dto) {
        Part part = partRepository.fetchById(dto.partId())
            .orElseThrow(() -> new ResourceNotFoundException("Peça/Insumo", dto.partId()));
        if (!part.isActive()) {
            throw new BusinessException("Peça/Insumo inativo: " + part.getName());
        }
        wo.addPart(part.getId(), part.getName(), dto.quantity(), part.getUnitPrice());
        try {
            part.decreaseStock(dto.quantity());
        } catch (BusinessException e) {
            metrics.transitionFailed(TransitionFailure.STOCK);
            throw e;
        }
        partRepository.save(part);
    }

    private static CustomerSnapshot snapshotOf(Client client) {
        return new CustomerSnapshot(client.getId(), client.getName(), client.getCpfCnpj(), client.getEmail());
    }

    private static VehicleSnapshot snapshotOf(Vehicle vehicle) {
        return new VehicleSnapshot(
            vehicle.getId(), vehicle.getLicensePlate(), vehicle.getBrand(),
            vehicle.getModel(), vehicle.getProductionYear());
    }
}
