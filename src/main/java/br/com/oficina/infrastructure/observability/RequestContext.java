package br.com.oficina.infrastructure.observability;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import java.util.Optional;
import org.jboss.logging.MDC;

/**
 * Contexto técnico da requisição ou mensagem em processamento: correlação (MDC, preenchido pelo
 * {@code CorrelationIdFilter} e pelo consumidor de mensagens), usuário autenticado e o trace atual.
 * Vai para o histórico de status e para o envelope das mensagens publicadas (spec 13 §1).
 */
public final class RequestContext {

    /** Mesmas chaves usadas pelos filtros HTTP ({@code CorrelationIdFilter}, {@code CallerMdcFilter}). */
    public static final String CORRELATION_ID = "correlationId";
    public static final String USERNAME = "username";

    private RequestContext() {
    }

    public static Optional<String> correlationId() {
        return mdc(CORRELATION_ID);
    }

    public static Optional<String> username() {
        return mdc(USERNAME);
    }

    /** {@code traceparent} W3C do span atual; vazio sem tracing ativo (SDK desligado em dev/test). */
    public static Optional<String> traceparent() {
        SpanContext span = Span.current().getSpanContext();
        if (!span.isValid()) {
            return Optional.empty();
        }
        String flags = span.isSampled() ? "01" : "00";
        return Optional.of("00-" + span.getTraceId() + "-" + span.getSpanId() + "-" + flags);
    }

    public static void putCorrelationId(String correlationId) {
        if (correlationId != null) {
            MDC.put(CORRELATION_ID, correlationId);
        }
    }

    public static void clearCorrelationId() {
        MDC.remove(CORRELATION_ID);
    }

    private static Optional<String> mdc(String key) {
        Object value = MDC.get(key);
        return value == null ? Optional.empty() : Optional.of(value.toString());
    }
}
