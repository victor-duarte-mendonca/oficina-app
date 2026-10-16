package br.com.oficina.application.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.oficina.domain.exception.ResourceNotFoundException;
import br.com.oficina.domain.model.CustomerSnapshot;
import br.com.oficina.domain.model.Vehicle;
import br.com.oficina.domain.model.VehicleSnapshot;
import br.com.oficina.domain.model.WorkOrder;
import br.com.oficina.testsupport.DomainTestFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class OwnershipGuardTest {

    private static final long OWNER_ID = 1L;
    private static final long OTHER_ID = 2L;

    private final OwnershipGuard guard = new OwnershipGuard();
    private WorkOrder workOrder;
    private Vehicle vehicle;

    @BeforeEach
    void setUp() {
        workOrder = new WorkOrder(
            new CustomerSnapshot(OWNER_ID, "Dono", "11144477735", "dono@x.com"),
            new VehicleSnapshot(10L, "ABC1234", "Fiat", "Uno", 2010),
            null);
        DomainTestFixtures.setId(workOrder, 77L);

        vehicle = new Vehicle("ABC1234", "Fiat", "Uno", 2010, OWNER_ID);
        DomainTestFixtures.setId(vehicle, 10L);
    }

    @Test
    void assertOwns_workOrder_shouldPassForOwner() {
        assertThatCode(() -> guard.assertOwns(caller(OWNER_ID), workOrder)).doesNotThrowAnyException();
    }

    @Test
    void assertOwns_workOrder_shouldAnswerNotFoundForAnotherClient() {
        assertThatThrownBy(() -> guard.assertOwns(caller(OTHER_ID), workOrder))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void assertOwns_vehicle_shouldPassForOwner() {
        assertThatCode(() -> guard.assertOwns(caller(OWNER_ID), vehicle)).doesNotThrowAnyException();
    }

    @Test
    void assertOwns_vehicle_shouldAnswerNotFoundForAnotherClient() {
        assertThatThrownBy(() -> guard.assertOwns(caller(OTHER_ID), vehicle))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    /** O 404 de "não é seu" tem de ser indistinguível do 404 de "não existe". */
    @Test
    void notOwned_shouldBeIndistinguishableFromNonexistent() {
        Throwable notOwned = org.assertj.core.api.Assertions.catchThrowable(
            () -> guard.assertOwns(caller(OTHER_ID), workOrder));
        Throwable nonexistent = new ResourceNotFoundException("Ordem de Serviço", 77L);

        assertThat(notOwned).hasMessage(nonexistent.getMessage());
    }

    private static AuthenticatedCaller caller(long clientId) {
        return new AuthenticatedCaller(clientId, "CLIENT");
    }
}
