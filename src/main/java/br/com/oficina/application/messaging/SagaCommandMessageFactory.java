package br.com.oficina.application.messaging;

import br.com.oficina.domain.model.VehicleSnapshot;
import br.com.oficina.domain.model.WorkOrder;
import br.com.oficina.domain.saga.SagaCommand;
import br.com.oficina.domain.saga.SagaPolicy;
import br.com.oficina.domain.saga.WorkOrderSaga;
import jakarta.enterprise.context.ApplicationScoped;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Monta as mensagens que o OS publica (Factory): o domínio decide <em>qual</em> comando, esta classe
 * preenche o conteúdo a partir da OS — o snapshot de itens, preços, cliente e veículo que os
 * participantes precisam, já que nenhum deles lê o banco do OS (R5).
 *
 * <p>Somente os dados mínimos de cada comando: nome e e-mail do cliente só vão para o Billing (que
 * envia o orçamento); o CPF/CNPJ nunca sai do OS.</p>
 */
@ApplicationScoped
public class SagaCommandMessageFactory {

    private static final int DEFAULT_PRIORITY = 0;

    private final SagaPolicy policy;

    public SagaCommandMessageFactory(SagaPolicy policy) {
        this.policy = policy;
    }

    public OutboundMessage commandFor(SagaCommand command, WorkOrderSaga saga, WorkOrder workOrder,
                                      UUID causationId, OffsetDateTime now) {
        Map<String, Object> payload = switch (command) {
            case SagaCommand.GenerateBudget ignored -> budgetRequest(workOrder);
            case SagaCommand.RequestPayment request -> ordered(
                "budgetId", request.budgetId(),
                "amount", money(workOrder.getTotalCost()),
                "attempt", request.attempt());
            case SagaCommand.VoidBudget voiding -> ordered("budgetId", voiding.budgetId(), "reason", voiding.reason());
            case SagaCommand.RefundPayment refund -> ordered("paymentId", refund.paymentId(), "reason", refund.reason());
            case SagaCommand.EnqueueExecution ignored -> ordered(
                "vehicle", vehicle(workOrder.getVehicle()),
                "items", executionItems(workOrder),
                "priority", DEFAULT_PRIORITY);
            case SagaCommand.CancelExecution cancel -> ordered("reason", cancel.reason());
        };
        return new OutboundMessage(UUID.randomUUID(), Destination.of(command.participant()), command.type(),
            MessageKind.COMMAND, saga.id(), workOrder.getId(), workOrder.getOrderNumber(), causationId, now, payload);
    }

    /** Informativo para o Execution abrir a fase de diagnóstico (fora da Saga: não espera resposta). */
    public OutboundMessage diagnosisStarted(WorkOrder workOrder, OffsetDateTime now) {
        Map<String, Object> payload = ordered("vehicle", vehicle(workOrder.getVehicle()), "notes", workOrder.getNotes());
        return new OutboundMessage(UUID.randomUUID(), Destination.EXECUTION_COMMANDS, "DiagnosisStarted",
            MessageKind.INFO, null, workOrder.getId(), workOrder.getOrderNumber(), null, now, payload);
    }

    private Map<String, Object> budgetRequest(WorkOrder workOrder) {
        Map<String, Object> customer = ordered(
            "name", workOrder.getCustomer().name(),
            "email", workOrder.getCustomer().email());
        return ordered(
            "clientId", workOrder.getCustomer().clientId(),
            "customer", customer,
            "items", budgetItems(workOrder),
            "total", money(workOrder.getTotalCost()),
            "approvalTtlSeconds", policy.approvalTimeout().toSeconds());
    }

    private static List<Map<String, Object>> budgetItems(WorkOrder workOrder) {
        Stream<Map<String, Object>> parts = workOrder.getParts().stream().map(part -> ordered(
            "kind", "PART", "refId", part.getPartId(), "name", part.getPartName(),
            "quantity", part.getQuantity(), "unitPrice", money(part.getUnitPrice())));
        Stream<Map<String, Object>> services = workOrder.getServices().stream().map(service -> ordered(
            "kind", "SERVICE", "refId", service.getServiceItemId(), "name", service.getServiceName(),
            "quantity", 1, "unitPrice", money(service.getPrice())));
        return Stream.concat(parts, services).toList();
    }

    private static List<Map<String, Object>> executionItems(WorkOrder workOrder) {
        Stream<Map<String, Object>> parts = workOrder.getParts().stream().map(part -> ordered(
            "kind", "PART", "name", part.getPartName(), "quantity", part.getQuantity()));
        Stream<Map<String, Object>> services = workOrder.getServices().stream().map(service -> ordered(
            "kind", "SERVICE", "name", service.getServiceName(), "quantity", 1));
        return Stream.concat(parts, services).toList();
    }

    private static Map<String, Object> vehicle(VehicleSnapshot vehicle) {
        return ordered("brand", vehicle.brand(), "model", vehicle.model(), "plate", vehicle.licensePlate());
    }

    /** Dinheiro trafega como texto com duas casas: nada de ponto flutuante no contrato. */
    static String money(BigDecimal value) {
        return (value == null ? BigDecimal.ZERO : value).setScale(2, RoundingMode.HALF_EVEN).toPlainString();
    }

    /** Mapa que preserva a ordem dos campos e aceita {@code null} (ex.: e-mail não informado). */
    private static Map<String, Object> ordered(Object... keyValues) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            map.put((String) keyValues[i], keyValues[i + 1]);
        }
        return map;
    }
}
