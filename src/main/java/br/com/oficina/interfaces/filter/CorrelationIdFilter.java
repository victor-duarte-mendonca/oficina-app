package br.com.oficina.interfaces.filter;

import io.quarkus.vertx.http.runtime.filters.Filters;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import org.jboss.logging.MDC;

/**
 * Correlação de requisições (R7): todo log da requisição carrega {@code correlationId} e toda resposta
 * devolve {@code X-Correlation-Id} — é o valor que se copia para achar a requisição no New Relic.
 *
 * <p>Registrado como filtro do Vert.x, e não como filtro JAX-RS, de propósito: roda antes da
 * autenticação e antes do CORS, então até a resposta 401/403 de segurança, que nunca chega ao
 * JAX-RS, leva o cabeçalho.</p>
 *
 * <p>Origem do id: {@code X-Correlation-Id}; senão {@code X-Amzn-Request-Id} (posto pelo API
 * Gateway); senão um UUID. Valor recebido só é aceito se for um token simples — um id com quebra
 * de linha ou muito longo permitiria forjar linhas no log.</p>
 */
@ApplicationScoped
public class CorrelationIdFilter {

    public static final String HEADER = "X-Correlation-Id";
    public static final String MDC_KEY = "correlationId";
    static final String GATEWAY_REQUEST_ID_HEADER = "X-Amzn-Request-Id";

    /** Executa antes de CORS e autenticação (prioridade maior = mais cedo). */
    static final int PRIORITY = 10_000;

    // "=" é aceito porque o $context.requestId do API Gateway HTTP termina em "=" (ex.: JIK9Ngt5IAMEJHg=);
    // não permite forjar linha de log.
    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9._:=-]{1,128}");

    /** Chaves de MDC desta requisição; todas saem do contexto quando a resposta termina. */
    static final List<String> MDC_KEYS = List.of(MDC_KEY, CallerMdcFilter.USERNAME_KEY, CallerMdcFilter.CLIENT_ID_KEY);

    void register(@Observes Filters filters) {
        filters.register(this::handle, PRIORITY);
    }

    void handle(RoutingContext context) {
        String correlationId = resolve(context.request());
        MDC.put(MDC_KEY, correlationId);
        context.response().putHeader(HEADER, correlationId);
        // Obrigatório: threads (e contextos) são reaproveitados; sem limpar, o id vazaria para outra requisição.
        context.addEndHandler(result -> MDC_KEYS.forEach(MDC::remove));
        context.next();
    }

    static String resolve(HttpServerRequest request) {
        return validOrNull(request.getHeader(HEADER))
            .or(() -> validOrNull(request.getHeader(GATEWAY_REQUEST_ID_HEADER)))
            .orElseGet(() -> UUID.randomUUID().toString());
    }

    private static java.util.Optional<String> validOrNull(String candidate) {
        return java.util.Optional.ofNullable(candidate).filter(value -> SAFE_ID.matcher(value).matches());
    }
}
