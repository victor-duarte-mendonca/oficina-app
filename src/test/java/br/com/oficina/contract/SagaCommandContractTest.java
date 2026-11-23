package br.com.oficina.contract;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.oficina.application.messaging.OutboundMessage;
import br.com.oficina.application.messaging.SagaCommandMessageFactory;
import br.com.oficina.domain.model.CustomerSnapshot;
import br.com.oficina.domain.model.VehicleSnapshot;
import br.com.oficina.domain.model.WorkOrder;
import br.com.oficina.domain.saga.SagaCommand;
import br.com.oficina.domain.saga.SagaPolicy;
import br.com.oficina.domain.saga.WorkOrderSaga;
import br.com.oficina.infrastructure.messaging.MessageEnvelopeCodec;
import br.com.oficina.testsupport.DomainTestFixtures;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Lado produtor do contrato: cada comando que o OS gera, depois de passar pelo codec real, é válido em
 * {@code os-commands.schema.json}. Uma mudança no código que quebre o contrato falha aqui antes de
 * chegar ao Billing ou ao Execution.
 */
class SagaCommandContractTest {

    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-11-23T14:00:00Z");

    private final SagaCommandMessageFactory factory = new SagaCommandMessageFactory(SagaPolicy.defaults());
    private final MessageEnvelopeCodec codec = new MessageEnvelopeCodec(ContractSchemas.MAPPER);
    private WorkOrder workOrder;
    private WorkOrderSaga saga;

    static Stream<SagaCommand> commands() {
        return Stream.of(
            new SagaCommand.GenerateBudget(),
            new SagaCommand.RequestPayment("b-1", 2),
            new SagaCommand.VoidBudget("b-1", "orçamento recusado"),
            new SagaCommand.RefundPayment("p-1", "falha na execução"),
            new SagaCommand.EnqueueExecution(),
            new SagaCommand.CancelExecution("cliente desistiu"));
    }

    @BeforeEach
    void setUp() {
        // E-mail nulo de propósito: o contrato aceita cliente sem e-mail, e o codec precisa gravar o null.
        workOrder = new WorkOrder(new CustomerSnapshot(7L, "Ana Souza", "11144477735", null),
            new VehicleSnapshot(3L, "ABC1D23", "Fiat", "Argo", 2021), "barulho no freio");
        workOrder.addPart(5L, "Pastilha de freio", 2, new BigDecimal("89.90"));
        workOrder.addService(9L, "Troca de pastilhas", new BigDecimal("150"), null);
        DomainTestFixtures.setId(workOrder, 42L);
        DomainTestFixtures.setField(workOrder, "orderNumber", "OS-000042");
        saga = WorkOrderSaga.newFor(UUID.randomUUID(), 42L);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("commands")
    void generatedCommand_shouldMatchTheContract(SagaCommand command) {
        OutboundMessage message = factory.commandFor(command, saga, workOrder, UUID.randomUUID(), NOW);

        JsonNode wire = wire(message, "4bf92f3577b34da6a3ce929d0e0e4736");

        assertThat(ContractSchemas.validate("os-commands", wire)).isEmpty();
        assertThat(wire.get("type").asText()).isEqualTo(command.type());
        assertThat(wire.get("sagaId").asText()).isEqualTo(saga.id().toString());
    }

    @Test
    void generateBudget_shouldCarryTheSnapshotTheBillingNeedsButNoPersonalDocument() {
        JsonNode wire = wire(factory.commandFor(new SagaCommand.GenerateBudget(), saga, workOrder, null, NOW), null);
        JsonNode payload = wire.get("payload");

        assertThat(payload.get("total").asText()).isEqualTo("329.80");
        assertThat(payload.get("items")).hasSize(2);
        assertThat(payload.get("items").get(0).get("unitPrice").asText()).isEqualTo("89.90");
        assertThat(payload.get("customer").has("email")).isTrue();
        assertThat(payload.get("customer").get("email").isNull()).isTrue();
        assertThat(wire.toString()).doesNotContain("11144477735");
        assertThat(payload.get("approvalTtlSeconds").asLong()).isEqualTo(72 * 3600);
    }

    @Test
    void diagnosisStarted_shouldBeAnInformationalMessageWithoutSaga() {
        JsonNode wire = wire(factory.diagnosisStarted(workOrder, NOW), null);

        assertThat(ContractSchemas.validate("os-commands", wire)).isEmpty();
        assertThat(wire.get("kind").asText()).isEqualTo("INFO");
        assertThat(wire.get("sagaId").isNull()).isTrue();
    }

    @Test
    void envelope_withTracingContext_shouldBeValid() {
        OutboundMessage message = factory.commandFor(new SagaCommand.EnqueueExecution(), saga, workOrder, null, NOW);
        String traceparent = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";

        JsonNode wire = read(codec.encode(message, "req-123", traceparent));

        assertThat(ContractSchemas.validate("os-commands", wire)).isEmpty();
        assertThat(wire.get("correlationId").asText()).isEqualTo("req-123");
        assertThat(wire.get("traceparent").asText()).isEqualTo(traceparent);
        assertThat(wire.get("occurredAt").asText()).isEqualTo("2026-11-23T14:00:00Z");
    }

    private JsonNode wire(OutboundMessage message, String correlationId) {
        return read(codec.encode(message, correlationId, null));
    }

    private static JsonNode read(String json) {
        try {
            return ContractSchemas.MAPPER.readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
