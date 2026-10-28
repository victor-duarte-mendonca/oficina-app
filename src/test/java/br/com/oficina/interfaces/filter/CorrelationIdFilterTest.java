package br.com.oficina.interfaces.filter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.vertx.core.Handler;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.ext.web.RoutingContext;
import org.jboss.logging.MDC;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

class CorrelationIdFilterTest {

    private final CorrelationIdFilter filter = new CorrelationIdFilter();

    @AfterEach
    void cleanMdc() {
        CorrelationIdFilter.MDC_KEYS.forEach(MDC::remove);
    }

    @Test
    void resolve_shouldPropagateTheIncomingCorrelationId() {
        assertThat(CorrelationIdFilter.resolve(request("abc-123", null))).isEqualTo("abc-123");
    }

    @Test
    void resolve_shouldFallBackToTheGatewayRequestId() {
        assertThat(CorrelationIdFilter.resolve(request(null, "1-67891233-abcdef012345678912345678")))
            .isEqualTo("1-67891233-abcdef012345678912345678");
    }

    /** O requestId do API Gateway HTTP ($context.requestId) termina em "=": não pode ser descartado. */
    @Test
    void resolve_shouldAcceptTheGatewayRequestIdFormat() {
        assertThat(CorrelationIdFilter.resolve(request("JIK9Ngt5IAMEJHg=", null))).isEqualTo("JIK9Ngt5IAMEJHg=");
    }

    @Test
    void resolve_shouldPreferCorrelationIdOverGatewayRequestId() {
        assertThat(CorrelationIdFilter.resolve(request("mine", "gateway"))).isEqualTo("mine");
    }

    @Test
    void resolve_shouldGenerateAUuidWhenAbsent() {
        assertThat(CorrelationIdFilter.resolve(request(null, null)))
            .matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    }

    /** Valor com quebra de linha ou gigante permitiria forjar linhas no log: é descartado. */
    @ParameterizedTest
    @ValueSource(strings = {"ok\nFORJADO level=ERROR", "com espaco", "<script>", ""})
    void resolve_shouldDiscardUnsafeValues(String unsafe) {
        assertThat(CorrelationIdFilter.resolve(request(unsafe, null))).isNotEqualTo(unsafe)
            .matches("[0-9a-f-]{36}");
    }

    @Test
    void resolve_shouldDiscardOversizedValues() {
        String oversized = "a".repeat(129);

        assertThat(CorrelationIdFilter.resolve(request(oversized, null))).isNotEqualTo(oversized);
    }

    @Test
    void handle_shouldPutTheIdInMdcAndOnTheResponseThenClearItWhenTheResponseEnds() {
        RoutingContext context = mock(RoutingContext.class);
        HttpServerResponse response = mock(HttpServerResponse.class);
        HttpServerRequest incoming = request("corr-1", null);
        when(context.request()).thenReturn(incoming);
        when(context.response()).thenReturn(response);

        filter.handle(context);

        assertThat(MDC.get(CorrelationIdFilter.MDC_KEY)).isEqualTo("corr-1");
        verify(response).putHeader("X-Correlation-Id", "corr-1");
        verify(context).next();

        // Quando a resposta termina, o MDC (inclusive o do chamador) precisa sair do contexto.
        MDC.put("username", "alguem");
        MDC.put("clientId", "7");
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Handler<io.vertx.core.AsyncResult<Void>>> endHandler = ArgumentCaptor.forClass(Handler.class);
        verify(context).addEndHandler(endHandler.capture());
        endHandler.getValue().handle(io.vertx.core.Future.succeededFuture());

        assertThat(MDC.get(CorrelationIdFilter.MDC_KEY)).isNull();
        assertThat(MDC.get("username")).isNull();
        assertThat(MDC.get("clientId")).isNull();
    }

    private static HttpServerRequest request(String correlationId, String gatewayRequestId) {
        HttpServerRequest request = mock(HttpServerRequest.class);
        when(request.getHeader("X-Correlation-Id")).thenReturn(correlationId);
        when(request.getHeader("X-Amzn-Request-Id")).thenReturn(gatewayRequestId);
        return request;
    }
}
