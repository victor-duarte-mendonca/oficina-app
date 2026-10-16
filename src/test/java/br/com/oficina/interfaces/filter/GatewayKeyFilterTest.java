package br.com.oficina.interfaces.filter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import br.com.oficina.application.ports.out.BusinessMetricsPort;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

class GatewayKeyFilterTest {

    private static final String KEY = "chave-do-gateway-de-teste";

    private final BusinessMetricsPort metrics = mock(BusinessMetricsPort.class);
    private final GatewayKeyFilter filter = new GatewayKeyFilter(Optional.of(KEY), metrics);

    @ParameterizedTest
    @ValueSource(strings = {"admin/clients", "/admin/work-orders/1", "me", "/me/work-orders", "me/vehicles"})
    void protectedPath_withoutHeader_shouldBeForbidden(String path) {
        ContainerRequestContext request = request(path, null);

        filter.filter(request);

        assertForbidden(request);
    }

    @Test
    void protectedPath_withWrongKey_shouldBeForbidden() {
        ContainerRequestContext request = request("/me/work-orders", "chave-errada");

        filter.filter(request);

        assertForbidden(request);
    }

    @Test
    void protectedPath_withKeyOfDifferentLength_shouldBeForbidden() {
        ContainerRequestContext request = request("/admin/clients", KEY + "x");

        filter.filter(request);

        assertForbidden(request);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/admin/clients", "me/work-orders"})
    void protectedPath_withCorrectKey_shouldPass(String path) {
        ContainerRequestContext request = request(path, KEY);

        filter.filter(request);

        verify(request, never()).abortWith(any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"q/health/live", "/q/health/ready", "q/metrics", "auth/login", "public/work-orders/OS-1/status",
        "administrator", "mevehicles"})
    void unprotectedPath_withoutHeader_shouldPass(String path) {
        ContainerRequestContext request = request(path, null);

        filter.filter(request);

        verify(request, never()).abortWith(any());
    }

    @Test
    void profileWithoutConfiguredKey_shouldDisableTheFilter() {
        GatewayKeyFilter disabled = new GatewayKeyFilter(Optional.empty(), metrics);
        ContainerRequestContext request = request("/admin/clients", null);

        disabled.filter(request);

        verify(request, never()).abortWith(any());
    }

    @Test
    void blankConfiguredKey_shouldDisableTheFilter() {
        GatewayKeyFilter disabled = new GatewayKeyFilter(Optional.of("   "), metrics);
        ContainerRequestContext request = request("/me", null);

        disabled.filter(request);

        verify(request, never()).abortWith(any());
    }

    private static ContainerRequestContext request(String path, String gatewayKeyHeader) {
        ContainerRequestContext request = mock(ContainerRequestContext.class);
        UriInfo uriInfo = mock(UriInfo.class);
        when(request.getUriInfo()).thenReturn(uriInfo);
        when(uriInfo.getPath()).thenReturn(path);
        when(request.getHeaderString(GatewayKeyFilter.HEADER)).thenReturn(gatewayKeyHeader);
        return request;
    }

    private void assertForbidden(ContainerRequestContext request) {
        ArgumentCaptor<Response> aborted = ArgumentCaptor.forClass(Response.class);
        verify(metrics).authDenied(org.mockito.ArgumentMatchers.startsWith("gateway_key"));
        verify(request).abortWith(aborted.capture());
        assertThat(aborted.getValue().getStatus()).isEqualTo(403);
    }
}
