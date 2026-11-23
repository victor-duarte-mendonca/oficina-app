package br.com.oficina.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.oficina.domain.exception.BusinessException;
import br.com.oficina.domain.exception.InvalidStatusTransitionException;
import java.math.BigDecimal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Transições da OS conduzidas pela Saga (spec 07 §2): cada uma só aceita o estado de origem correto. */
class WorkOrderSagaTransitionsTest {

    private WorkOrder workOrder;

    @BeforeEach
    void setUp() {
        workOrder = new WorkOrder(
            new CustomerSnapshot(1L, "Ana", "11144477735", "ana@x.com"),
            new VehicleSnapshot(1L, "ABC1D23", "Fiat", "Uno", 2015), null);
        workOrder.addService(1L, "Revisão", new BigDecimal("200.00"), null);
        workOrder.startDiagnosis();
    }

    @Test
    void fullSagaPath_shouldRecordTheInstantOfEachStatus() {
        workOrder.submitForBudget();
        assertThat(workOrder.getStatus()).isEqualTo(WorkOrderStatus.AWAITING_APPROVAL);
        assertThat(workOrder.statusEnteredAt()).contains(workOrder.getSentForApprovalAt());

        workOrder.awaitPayment();
        assertThat(workOrder.getStatus()).isEqualTo(WorkOrderStatus.AWAITING_PAYMENT);
        assertThat(workOrder.statusEnteredAt()).contains(workOrder.getApprovedAt());

        workOrder.queueForExecution();
        assertThat(workOrder.getStatus()).isEqualTo(WorkOrderStatus.AWAITING_EXECUTION);
        assertThat(workOrder.statusEnteredAt()).contains(workOrder.getQueuedForExecutionAt());

        workOrder.startExecution();
        workOrder.complete();
        assertThat(workOrder.getStatus()).isEqualTo(WorkOrderStatus.FINISHED);
        assertThat(workOrder.getExecutionStartedAt()).isNotNull();
    }

    @Test
    void submitForBudget_shouldNotIssueTheLocalApprovalToken() {
        workOrder.submitForBudget();

        // No fluxo da Saga o token de aprovação pertence ao Billing.
        assertThat(workOrder.getApprovalToken()).isNull();
        assertThat(workOrder.getTotalCost()).isEqualByComparingTo("200.00");
    }

    @Test
    void submittedOrder_shouldNoLongerBeEditable() {
        workOrder.submitForBudget();
        assertThatThrownBy(() -> workOrder.addService(2L, "Extra", BigDecimal.ONE, null))
            .isInstanceOf(BusinessException.class);
    }

    @Test
    void revertToDiagnosis_shouldReopenTheOrderForEditing() {
        workOrder.submitForBudget();
        workOrder.revertToDiagnosis();

        assertThat(workOrder.getStatus()).isEqualTo(WorkOrderStatus.IN_DIAGNOSIS);
        assertThat(workOrder.getSentForApprovalAt()).isNull();
        workOrder.addService(2L, "Alinhamento", new BigDecimal("80.00"), null);
        assertThat(workOrder.getTotalCost()).isEqualByComparingTo("280.00");
    }

    @Test
    void transitionsOutOfOrder_shouldBeRejected() {
        assertThatThrownBy(workOrder::awaitPayment).isInstanceOf(InvalidStatusTransitionException.class);
        assertThatThrownBy(workOrder::queueForExecution).isInstanceOf(InvalidStatusTransitionException.class);
        assertThatThrownBy(workOrder::startExecution).isInstanceOf(InvalidStatusTransitionException.class);
        assertThatThrownBy(workOrder::revertToDiagnosis).isInstanceOf(InvalidStatusTransitionException.class);
    }

    @Test
    void cancel_shouldBeAllowedFromTheNewWaitingStatuses() {
        workOrder.submitForBudget();
        workOrder.awaitPayment();
        workOrder.queueForExecution();

        workOrder.cancel();

        assertThat(workOrder.getStatus()).isEqualTo(WorkOrderStatus.CANCELLED);
        assertThat(workOrder.getCancelledAt()).isNotNull();
    }
}
