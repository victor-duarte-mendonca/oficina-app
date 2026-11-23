package br.com.oficina.application.messaging;

import br.com.oficina.domain.saga.SagaEvent;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * Traduz o contrato de mensagens (spec 03 §3.2) para os eventos de domínio da Saga — a camada
 * anticorrupção entre o formato externo e o modelo interno.
 *
 * <p>Registro por tipo (um tradutor por {@code type}): suportar um evento novo é uma linha aqui e um
 * caso nos handlers do domínio. Tipo desconhecido devolve vazio — o orquestrador ignora e registra, o
 * que permite a um participante publicar tipos novos antes de o OS conhecê-los.</p>
 */
@ApplicationScoped
public class SagaEventTranslator {

    private static final Map<String, Function<MessagePayload, SagaEvent>> TRANSLATORS = Map.ofEntries(
        Map.entry("BudgetGenerated", p -> new SagaEvent.BudgetGenerated(p.text("budgetId"))),
        Map.entry("BudgetGenerationFailed", p -> new SagaEvent.BudgetGenerationFailed(p.text("reason"))),
        Map.entry("BudgetApproved", p -> new SagaEvent.BudgetApproved(p.text("budgetId"), p.text("by"))),
        Map.entry("BudgetRejected", p -> new SagaEvent.BudgetRejected(p.text("budgetId"), p.text("by"))),
        Map.entry("BudgetExpired", p -> new SagaEvent.BudgetExpired(p.text("budgetId"))),
        Map.entry("BudgetVoided", p -> new SagaEvent.BudgetVoided(p.text("budgetId"))),
        Map.entry("PaymentRequested", p -> new SagaEvent.PaymentRequested(p.text("paymentId"))),
        Map.entry("PaymentRequestFailed", p -> new SagaEvent.PaymentRequestFailed(p.text("reason"))),
        Map.entry("PaymentApproved", p -> new SagaEvent.PaymentApproved(p.text("paymentId"))),
        Map.entry("PaymentRejected", p -> new SagaEvent.PaymentRejected(p.text("paymentId"), p.text("detail"))),
        Map.entry("PaymentExpired", p -> new SagaEvent.PaymentExpired(p.text("paymentId"))),
        Map.entry("PaymentRefunded", p -> new SagaEvent.PaymentRefunded(p.text("paymentId"))),
        Map.entry("PaymentRefundFailed", p -> new SagaEvent.PaymentRefundFailed(p.text("paymentId"), p.text("detail"))),
        Map.entry("ExecutionEnqueued", p -> new SagaEvent.ExecutionEnqueued(p.integer("position"))),
        Map.entry("ExecutionEnqueueFailed", p -> new SagaEvent.ExecutionEnqueueFailed(p.text("reason"))),
        Map.entry("ExecutionStarted", p -> new SagaEvent.ExecutionStarted(p.text("assignee"))),
        Map.entry("ExecutionFinished", p -> new SagaEvent.ExecutionFinished(p.optionalText("summary").orElse(""))),
        Map.entry("ExecutionFailed", p -> new SagaEvent.ExecutionFailed(p.text("reason"))),
        Map.entry("ExecutionCancelled", p -> new SagaEvent.ExecutionCancelled(p.flag("alreadyFinished")))
    );

    public Optional<SagaEvent> translate(InboundMessage message) {
        return Optional.ofNullable(TRANSLATORS.get(message.type()))
            .map(translator -> translator.apply(message.payload()));
    }

    /** Tipos de evento que movem a Saga (usado pelo teste de contrato para provar cobertura do catálogo). */
    public static java.util.Set<String> supportedTypes() {
        return TRANSLATORS.keySet();
    }
}
