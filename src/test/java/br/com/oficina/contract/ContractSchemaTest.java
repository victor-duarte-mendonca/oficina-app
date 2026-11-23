package br.com.oficina.contract;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.oficina.application.messaging.InboundMessage;
import br.com.oficina.application.messaging.SagaEventTranslator;
import br.com.oficina.domain.saga.SagaEvent;
import br.com.oficina.infrastructure.messaging.MessageEnvelopeCodec;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Lado consumidor do contrato (spec 03 §4): todo exemplo de evento publicado pelos participantes é
 * válido no schema do produtor <b>e</b> é lido pelo OS sem perda — o codec decodifica e o tradutor o
 * converte no evento de domínio. Os exemplos inválidos provam que o schema recusa o que deve recusar.
 */
class ContractSchemaTest {

    private final MessageEnvelopeCodec codec = new MessageEnvelopeCodec(ContractSchemas.MAPPER);
    private final SagaEventTranslator translator = new SagaEventTranslator();

    static Stream<Arguments> validEvents() {
        return Stream.of("billing-events", "execution-events").flatMap(schema ->
            elements(ContractSchemas.read("examples/" + schema + ".json"))
                .map(message -> Arguments.of(schema, message.get("type").asText(), message)));
    }

    static Stream<Arguments> invalidMessages() {
        return elements(ContractSchemas.read("examples/invalid-messages.json"))
            .map(example -> Arguments.of(example.get("schema").asText(), example.get("why").asText(),
                example.get("message")));
    }

    @ParameterizedTest(name = "{1} é válido em {0}")
    @MethodSource("validEvents")
    void example_shouldMatchTheProducerSchema(String schema, String type, JsonNode message) {
        assertThat(ContractSchemas.validate(schema, message)).as(type).isEmpty();
    }

    @ParameterizedTest(name = "{1} é lido pelo OS")
    @MethodSource("validEvents")
    void example_shouldBeDecodedAndTranslatedByTheOrchestrator(String schema, String type, JsonNode message) {
        InboundMessage inbound = codec.decode(message.toString());

        assertThat(inbound.type()).isEqualTo(type);
        assertThat(inbound.workOrderId()).isEqualTo(1L);
        Optional<SagaEvent> event = translator.translate(inbound);
        if ("INFO".equals(message.get("kind").asText())) {
            assertThat(event).as("informativo não move a Saga").isEmpty();
        } else {
            assertThat(event).as(type).isPresent();
            assertThat(event.get().type()).isEqualTo(type);
        }
    }

    @ParameterizedTest(name = "recusado: {1}")
    @MethodSource("invalidMessages")
    void invalidExample_shouldBeRejected(String schema, String why, JsonNode message) {
        assertThat(ContractSchemas.validate(schema, message)).as(why).isNotEmpty();
    }

    @Test
    void everySagaEventOfTheCatalog_shouldHaveAnExampleAndATranslator() {
        Set<String> catalog = new HashSet<>();
        Stream.of("billing-events", "execution-events").forEach(schema -> {
            JsonNode typeEnum = ContractSchemas.read(schema + ".schema.json").at("/allOf/1/properties/type/enum");
            elements(typeEnum).map(JsonNode::asText).forEach(catalog::add);
        });
        catalog.remove("ExecutionProgress"); // informativo: lido, mas não traduzido em evento de Saga

        Set<String> examples = new HashSet<>();
        validEvents().forEach(arguments -> examples.add((String) arguments.get()[1]));

        assertThat(SagaEventTranslator.supportedTypes()).containsExactlyInAnyOrderElementsOf(catalog);
        assertThat(examples).containsAll(catalog);
    }

    private static Stream<JsonNode> elements(JsonNode array) {
        return StreamSupport.stream(array.spliterator(), false);
    }
}
