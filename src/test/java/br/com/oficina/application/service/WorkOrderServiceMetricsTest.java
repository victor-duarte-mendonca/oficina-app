package br.com.oficina.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import br.com.oficina.application.dto.WorkOrderCreateDto;
import br.com.oficina.application.dto.WorkOrderPartDto;
import br.com.oficina.application.ports.in.CreateWorkOrderUseCase.Origin;
import br.com.oficina.application.ports.out.BusinessMetricsPort;
import br.com.oficina.application.ports.out.BusinessMetricsPort.Integration;
import br.com.oficina.application.ports.out.BusinessMetricsPort.TransitionFailure;
import br.com.oficina.application.ports.out.ClientRepositoryPort;
import br.com.oficina.application.ports.out.NotificationGatewayPort;
import br.com.oficina.application.ports.out.PartRepositoryPort;
import br.com.oficina.application.ports.out.ServiceItemRepositoryPort;
import br.com.oficina.application.ports.out.VehicleRepositoryPort;
import br.com.oficina.application.ports.out.WorkOrderRepositoryPort;
import br.com.oficina.domain.exception.BusinessException;
import br.com.oficina.domain.exception.InvalidStatusTransitionException;
import br.com.oficina.domain.model.Client;
import br.com.oficina.domain.model.ClientType;
import br.com.oficina.domain.model.CustomerSnapshot;
import br.com.oficina.domain.model.Part;
import br.com.oficina.domain.model.Vehicle;
import br.com.oficina.domain.model.VehicleSnapshot;
import br.com.oficina.domain.model.WorkOrder;
import br.com.oficina.domain.model.WorkOrderStatus;
import br.com.oficina.testsupport.DomainTestFixtures;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** A instrumentação de negócio do {@code WorkOrderService}: o que é contado e quando. */
@ExtendWith(MockitoExtension.class)
class WorkOrderServiceMetricsTest {

    @Mock WorkOrderRepositoryPort workOrderRepository;
    @Mock ClientRepositoryPort clientRepository;
    @Mock VehicleRepositoryPort vehicleRepository;
    @Mock PartRepositoryPort partRepository;
    @Mock ServiceItemRepositoryPort serviceItemRepository;
    @Mock NotificationGatewayPort notificationGateway;
    @Mock BusinessMetricsPort metrics;

    WorkOrderService service;
    WorkOrder workOrder;

    @BeforeEach
    void setUp() {
        service = new WorkOrderService(workOrderRepository, clientRepository, vehicleRepository,
            partRepository, serviceItemRepository, notificationGateway, metrics);

        workOrder = new WorkOrder(
            new CustomerSnapshot(1L, "Cliente", "11144477735", "c@x.com"),
            new VehicleSnapshot(1L, "ABC1234", "Fiat", "Uno", 2010), null);
        DomainTestFixtures.setId(workOrder, 1L);
        DomainTestFixtures.setField(workOrder, "createdAt", OffsetDateTime.now().minusMinutes(30));

        lenient().when(workOrderRepository.fetchById(1L)).thenReturn(Optional.of(workOrder));
        lenient().when(workOrderRepository.save(any(WorkOrder.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void create_shouldCountTheOrderWithItsOrigin() {
        Client client = DomainTestFixtures.setId(new Client("C", "11144477735", ClientType.PF, null, null), 1L);
        Vehicle vehicle = DomainTestFixtures.setId(new Vehicle("ABC1234", "Fiat", "Uno", 2010, 1L), 1L);
        when(clientRepository.findByCpfCnpj("11144477735")).thenReturn(Optional.of(client));
        when(vehicleRepository.fetchById(1L)).thenReturn(Optional.of(vehicle));

        service.create(new WorkOrderCreateDto("11144477735", 1L, null, null, null), Origin.CLIENT);

        verify(metrics).workOrderCreated(Origin.CLIENT);
    }

    @Test
    void create_withoutExplicitOrigin_shouldCountAsStaff() {
        Client client = DomainTestFixtures.setId(new Client("C", "11144477735", ClientType.PF, null, null), 1L);
        Vehicle vehicle = DomainTestFixtures.setId(new Vehicle("ABC1234", "Fiat", "Uno", 2010, 1L), 1L);
        when(clientRepository.findByCpfCnpj("11144477735")).thenReturn(Optional.of(client));
        when(vehicleRepository.fetchById(1L)).thenReturn(Optional.of(vehicle));

        service.create(new WorkOrderCreateDto("11144477735", 1L, null, null, null));

        verify(metrics).workOrderCreated(Origin.STAFF);
    }

    @Test
    void transition_shouldRecordFromToAndTimeSpentInTheStatusItLeft() {
        service.startDiagnosis(1L);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Optional<Duration>> time = ArgumentCaptor.forClass(Optional.class);
        verify(metrics).statusTransition(eq(WorkOrderStatus.RECEIVED), eq(WorkOrderStatus.IN_DIAGNOSIS), time.capture());
        assertThat(time.getValue()).hasValueSatisfying(
            duration -> assertThat(duration).isBetween(Duration.ofMinutes(29), Duration.ofMinutes(31)));
    }

    @Test
    void transition_whenEntryInstantIsUnknown_shouldRecordWithoutDuration() {
        DomainTestFixtures.setField(workOrder, "createdAt", null);

        service.startDiagnosis(1L);

        verify(metrics).statusTransition(WorkOrderStatus.RECEIVED, WorkOrderStatus.IN_DIAGNOSIS, Optional.empty());
    }

    @Test
    void invalidTransition_shouldBeCountedAndRethrownWithoutRecordingATransition() {
        // RECEIVED -> AWAITING_APPROVAL não é permitido (exige IN_DIAGNOSIS).
        assertThatThrownBy(() -> service.sendForApproval(1L))
            .isInstanceOf(InvalidStatusTransitionException.class);

        verify(metrics).transitionFailed(TransitionFailure.INVALID_TRANSITION);
        verify(metrics, never()).statusTransition(any(), any(), any());
    }

    @Test
    void persistenceFailure_shouldBeCountedAndRethrown() {
        when(workOrderRepository.save(any(WorkOrder.class))).thenThrow(new IllegalStateException("db fora"));

        assertThatThrownBy(() -> service.startDiagnosis(1L)).isInstanceOf(IllegalStateException.class);

        verify(metrics).transitionFailed(TransitionFailure.PERSISTENCE);
        verify(metrics, never()).statusTransition(any(), any(), any());
    }

    @Test
    void insufficientStock_shouldBeCountedAsStockFailure() {
        Part part = DomainTestFixtures.setId(new Part("Óleo", null, new BigDecimal("10.00"), 1, "L"), 5L);
        when(partRepository.fetchById(5L)).thenReturn(Optional.of(part));

        assertThatThrownBy(() -> service.addPart(1L, new WorkOrderPartDto(5L, 10)))
            .isInstanceOf(BusinessException.class);

        verify(metrics).transitionFailed(TransitionFailure.STOCK);
    }

    @Test
    void notificationFailure_shouldBeCountedAsSmtpAndKeepTheTransition() {
        DomainTestFixtures.setField(workOrder, "status", WorkOrderStatus.IN_EXECUTION);
        doThrow(new IllegalStateException("smtp fora")).when(notificationGateway).notifyStatusChange(any());

        service.complete(1L);

        verify(metrics).integrationFailed(Integration.SMTP);
        verify(metrics).statusTransition(eq(WorkOrderStatus.IN_EXECUTION), eq(WorkOrderStatus.FINISHED), any());
    }
}
