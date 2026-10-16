package br.com.oficina.interfaces.filter;

import jakarta.annotation.Priority;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.ext.Provider;
import java.security.Principal;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.jboss.logging.MDC;

/**
 * Acrescenta ao MDC quem está chamando ({@code username} e, para o cliente, {@code clientId}),
 * junto do {@code correlationId} já posto por {@link CorrelationIdFilter}. Roda depois da
 * autenticação — é a primeira hora em que a identidade existe. A limpeza é do filtro de correlação.
 */
@Provider
@Priority(Priorities.USER)
public class CallerMdcFilter implements ContainerRequestFilter {

    static final String USERNAME_KEY = "username";
    static final String CLIENT_ID_KEY = "clientId";

    @Override
    public void filter(ContainerRequestContext request) {
        Principal principal = request.getSecurityContext().getUserPrincipal();
        if (principal == null) {
            return;
        }
        MDC.put(USERNAME_KEY, principal.getName());
        if (principal instanceof JsonWebToken jwt && jwt.getClaim("clientId") != null) {
            MDC.put(CLIENT_ID_KEY, jwt.getClaim("clientId").toString());
        }
    }
}
