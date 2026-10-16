package br.com.oficina.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import br.com.oficina.application.dto.ApprovalDecisionDto;
import br.com.oficina.application.dto.ApprovalDecisionDto.Decision;
import br.com.oficina.application.dto.ClientWorkOrderRequestDto;
import br.com.oficina.application.dto.WorkOrderCreateDto;
import br.com.oficina.application.dto.WorkOrderResponseDto;
import br.com.oficina.application.ports.in.ChangeWorkOrderStatusUseCase;
import br.com.oficina.application.ports.in.CreateWorkOrderUseCase;
import br.com.oficina.application.ports.out.ClientRepositoryPort;
import br.com.oficina.application.ports.out.VehicleRepositoryPort;
import br.com.oficina.application.ports.out.WorkOrderRepositoryPort;
import br.com.oficina.application.security.AuthenticatedCaller;
import br.com.oficina.application.security.OwnershipGuard;
import br.com.oficina.domain.exception.ResourceNotFoundException;
import br.com.oficina.domain.model.Client;
import br.com.oficina.domain.model.ClientType;
import br.com.oficina.domain.model.CustomerSnapshot;
import br.com.oficina.domain.model.Vehicle;
import br.com.oficina.domain.model.VehicleSnapshot;
import br.com.oficina.domain.model.WorkOrder;
import br.com.oficina.testsupport.DomainTestFixtures;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Casos de uso da área do cliente, sem {@code @QuarkusTest}: o caller entra como parâmetro. */
@ExtendWith(MockitoExtension.class)
class ClientSelfServiceTest {

    private static final long ME = 1L;
    private static final long SOMEONE_ELSE = 2L;

    @Mock ClientRepositoryPort clientRepository;
    @Mock VehicleRepositoryPort vehicleRepository;
    @Mock WorkOrderRepositoryPort workOrderRepository;
    @Mock CreateWorkOrderUseCase createWorkOrder;
    @Mock ChangeWorkOrderStatusUseCase changeStatus;

    ClientSelfService service;
    AuthenticatedCaller caller;

    @BeforeEach
    void setUp() {
        service = new ClientSelfService(clientRepository, vehicleRepository, workOrderRepository,
            new OwnershipGuard(), createWorkOrder, changeStatus);
        caller = new AuthenticatedCaller(ME, "CLIENT");
    }

    @Test
    void profile_shouldLoadTheCallersOwnClient() {
        Client client = client(ME);
        when(clientRepository.fetchById(ME)).thenReturn(Optional.of(client));

        assertThat(service.profile(caller).cpfCnpj()).isEqualTo("11144477735");
    }

    @Test
    void vehicles_shouldQueryByTheTokenClientId() {
        when(vehicleRepository.findByClientId(ME)).thenReturn(List.of(vehicle(10L, ME)));

        assertThat(service.vehicles(caller)).hasSize(1);
        verify(vehicleRepository).findByClientId(ME);
    }

    @Test
    void listWorkOrders_shouldQueryByTheTokenClientIdAndCapPageSize() {
        when(workOrderRepository.findByClientId(ME, 0, 100)).thenReturn(List.of(workOrder(5L, ME)));

        assertThat(service.listWorkOrders(caller, -3, 5000)).hasSize(1);
        verify(workOrderRepository).findByClientId(ME, 0, 100);
    }

    @Test
    void findWorkOrder_ofAnotherClient_shouldBeNotFound() {
        when(workOrderRepository.fetchById(5L)).thenReturn(Optional.of(workOrder(5L, SOMEONE_ELSE)));

        assertThatThrownBy(() -> service.findWorkOrder(caller, 5L))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void findWorkOrder_ofTheCaller_shouldReturnIt() {
        when(workOrderRepository.fetchById(5L)).thenReturn(Optional.of(workOrder(5L, ME)));

        assertThat(service.findWorkOrder(caller, 5L).id()).isEqualTo(5L);
    }

    @Test
    void openWorkOrder_shouldDelegateWithTheCallersCpfAndNoServicesOrParts() {
        when(vehicleRepository.fetchById(10L)).thenReturn(Optional.of(vehicle(10L, ME)));
        when(clientRepository.fetchById(ME)).thenReturn(Optional.of(client(ME)));
        WorkOrderResponseDto created = WorkOrderResponseDto.from(workOrder(9L, ME));
        when(createWorkOrder.create(any())).thenReturn(created);

        service.openWorkOrder(caller, new ClientWorkOrderRequestDto(10L, "barulho no freio"));

        ArgumentCaptor<WorkOrderCreateDto> sent = ArgumentCaptor.forClass(WorkOrderCreateDto.class);
        verify(createWorkOrder).create(sent.capture());
        assertThat(sent.getValue().clientCpfCnpj()).isEqualTo("11144477735");
        assertThat(sent.getValue().vehicleId()).isEqualTo(10L);
        assertThat(sent.getValue().services()).isNull();
        assertThat(sent.getValue().parts()).isNull();
    }

    @Test
    void openWorkOrder_withAnotherClientsVehicle_shouldBeNotFoundAndNeverCreate() {
        when(vehicleRepository.fetchById(10L)).thenReturn(Optional.of(vehicle(10L, SOMEONE_ELSE)));

        assertThatThrownBy(() -> service.openWorkOrder(caller, new ClientWorkOrderRequestDto(10L, null)))
            .isInstanceOf(ResourceNotFoundException.class);
        verify(createWorkOrder, never()).create(any());
    }

    @Test
    void decideApproval_approve_shouldDelegateToTheStatusUseCase() {
        when(workOrderRepository.fetchById(5L)).thenReturn(Optional.of(workOrder(5L, ME)));

        service.decideApproval(caller, 5L, new ApprovalDecisionDto(Decision.APPROVE));

        verify(changeStatus).approve(5L);
        verify(changeStatus, never()).reject(any());
    }

    @Test
    void decideApproval_reject_shouldDelegateToTheStatusUseCase() {
        when(workOrderRepository.fetchById(5L)).thenReturn(Optional.of(workOrder(5L, ME)));

        service.decideApproval(caller, 5L, new ApprovalDecisionDto(Decision.REJECT));

        verify(changeStatus).reject(5L);
    }

    @Test
    void decideApproval_onAnotherClientsOrder_shouldBeNotFoundAndChangeNothing() {
        when(workOrderRepository.fetchById(5L)).thenReturn(Optional.of(workOrder(5L, SOMEONE_ELSE)));

        assertThatThrownBy(() -> service.decideApproval(caller, 5L, new ApprovalDecisionDto(Decision.APPROVE)))
            .isInstanceOf(ResourceNotFoundException.class);
        verify(changeStatus, never()).approve(any());
        verify(changeStatus, never()).reject(any());
    }

    private static Client client(long id) {
        Client client = new Client("Cliente", "11144477735", ClientType.PF, null, null);
        return DomainTestFixtures.setId(client, id);
    }

    private static Vehicle vehicle(long id, long ownerId) {
        Vehicle vehicle = new Vehicle("ABC1234", "Fiat", "Uno", 2010, ownerId);
        return DomainTestFixtures.setId(vehicle, id);
    }

    private static WorkOrder workOrder(long id, long ownerId) {
        WorkOrder workOrder = new WorkOrder(
            new CustomerSnapshot(ownerId, "Cliente", "11144477735", null),
            new VehicleSnapshot(10L, "ABC1234", "Fiat", "Uno", 2010),
            null);
        return DomainTestFixtures.setId(workOrder, id);
    }
}
