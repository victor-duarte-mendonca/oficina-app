package br.com.oficina.interfaces.filter;

import jakarta.annotation.Priority;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import java.util.Optional;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Garante que {@code /admin/*} e {@code /me/*} só chegam pelo API Gateway: exige o header
 * {@code X-Gateway-Key}, que o gateway injeta e o cliente final não conhece.
 *
 * <p>Não se aplica a {@code /q/health} (o kubelet chama direto no pod), a {@code /q/metrics}
 * (scrape interno), a {@code /auth} nem a {@code /public}. Com {@code app.gateway.key} vazio
 * (dev/test/docker) o filtro fica desligado — senão todo desenvolvimento local passaria a exigir
 * o header e a chave real acabaria copiada para a máquina de quem desenvolve.</p>
 */
@Provider
@Priority(Priorities.AUTHENTICATION - 100)
public class GatewayKeyFilter implements ContainerRequestFilter {

    static final String HEADER = "X-Gateway-Key";

    private final byte[] expectedKey;

    public GatewayKeyFilter(@ConfigProperty(name = "app.gateway.key") Optional<String> gatewayKey) {
        this.expectedKey = gatewayKey.filter(key -> !key.isBlank())
            .map(key -> key.getBytes(StandardCharsets.UTF_8))
            .orElse(null);
    }

    @Override
    public void filter(ContainerRequestContext request) {
        if (expectedKey == null || !isGatewayProtected(request.getUriInfo().getPath())) {
            return;
        }
        String provided = request.getHeaderString(HEADER);
        if (provided == null || !matches(provided)) {
            request.abortWith(Response.status(Response.Status.FORBIDDEN)
                .type(MediaType.APPLICATION_JSON)
                .entity(Map.of("status", 403, "error", "Forbidden", "message", "Acesso negado"))
                .build());
        }
    }

    /** Comparação em tempo constante: não revela, pelo tempo de resposta, quantos bytes batem. */
    private boolean matches(String provided) {
        return MessageDigest.isEqual(provided.getBytes(StandardCharsets.UTF_8), expectedKey);
    }

    static boolean isGatewayProtected(String path) {
        String normalized = path.startsWith("/") ? path.substring(1) : path;
        return isUnder(normalized, "admin") || isUnder(normalized, "me");
    }

    private static boolean isUnder(String path, String prefix) {
        return path.equals(prefix) || path.startsWith(prefix + "/");
    }
}
