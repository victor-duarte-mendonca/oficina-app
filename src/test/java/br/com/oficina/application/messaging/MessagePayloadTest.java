package br.com.oficina.application.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Mensagem malformada nunca move a Saga com dados inventados: vira {@link InvalidMessageException}. */
class MessagePayloadTest {

    private final SagaEventTranslator translator = new SagaEventTranslator();

    @Test
    void requiredFields_shouldBeReadWithTheirTypes() {
        MessagePayload payload = new MessagePayload(Map.of("id", "p-1", "position", 3, "done", true));

        assertThat(payload.text("id")).isEqualTo("p-1");
        assertThat(payload.integer("position")).isEqualTo(3);
        assertThat(payload.flag("done")).isTrue();
    }

    @Test
    void missingOrWronglyTypedField_shouldBeRejected() {
        Map<String, Object> values = new HashMap<>();
        values.put("blank", " ");
        values.put("number", "três");
        values.put("nothing", null);
        MessagePayload payload = new MessagePayload(values);

        assertThatThrownBy(() -> payload.text("blank")).isInstanceOf(InvalidMessageException.class);
        assertThatThrownBy(() -> payload.integer("number")).isInstanceOf(InvalidMessageException.class);
        assertThatThrownBy(() -> payload.text("nothing")).isInstanceOf(InvalidMessageException.class);
        assertThatThrownBy(() -> payload.flag("number")).isInstanceOf(InvalidMessageException.class);
        assertThat(payload.optionalText("nothing")).isEmpty();
    }

    @Test
    void translator_shouldRejectAnEventWithoutItsRequiredField() {
        InboundMessage malformed = InboundMessage.of(UUID.randomUUID(), "PaymentApproved", MessageKind.EVENT,
            UUID.randomUUID(), 1L, Map.of("amount", "10.00"));

        assertThatThrownBy(() -> translator.translate(malformed))
            .isInstanceOf(InvalidMessageException.class)
            .hasMessageContaining("paymentId");
    }

    @Test
    void translator_shouldIgnoreUnknownTypes() {
        InboundMessage future = InboundMessage.of(UUID.randomUUID(), "BudgetDiscountApplied", MessageKind.EVENT,
            UUID.randomUUID(), 1L, Map.of());

        assertThat(translator.translate(future)).isEmpty();
    }
}
