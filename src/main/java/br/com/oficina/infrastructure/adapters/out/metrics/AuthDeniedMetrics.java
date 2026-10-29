package br.com.oficina.infrastructure.adapters.out.metrics;

import br.com.oficina.application.ports.out.BusinessMetricsPort;
import io.quarkus.security.spi.runtime.AuthenticationFailureEvent;
import io.quarkus.security.spi.runtime.AuthorizationFailureEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;

/**
 * Conta as tentativas de acesso negadas pela camada de segurança do Quarkus — 401 (token ausente
 * ou inválido) e 403 (papel insuficiente). Esses erros nascem antes do JAX-RS, então nem um
 * {@code ExceptionMapper} nem um filtro de resposta os enxergam; os eventos de segurança, sim.
 */
@ApplicationScoped
public class AuthDeniedMetrics {

    static final String UNAUTHENTICATED = "unauthenticated";
    static final String FORBIDDEN = "forbidden";

    private final BusinessMetricsPort metrics;

    public AuthDeniedMetrics(BusinessMetricsPort metrics) {
        this.metrics = metrics;
    }

    void onAuthenticationFailure(@Observes AuthenticationFailureEvent event) {
        metrics.authDenied(UNAUTHENTICATED);
    }

    /**
     * Sem token, o Quarkus autentica como anônimo e falha na autorização (a resposta é 401): é
     * "não autenticado". Com identidade real e papel insuficiente (403), é "forbidden".
     */
    void onAuthorizationFailure(@Observes AuthorizationFailureEvent event) {
        boolean anonymous = event.getSecurityIdentity() == null || event.getSecurityIdentity().isAnonymous();
        metrics.authDenied(anonymous ? UNAUTHENTICATED : FORBIDDEN);
    }
}
