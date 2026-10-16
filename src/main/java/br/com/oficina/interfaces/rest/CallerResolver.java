package br.com.oficina.interfaces.rest;

import br.com.oficina.application.security.AuthenticatedCaller;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.ForbiddenException;
import org.eclipse.microprofile.jwt.JsonWebToken;

/**
 * Traduz o {@link JsonWebToken} no {@link AuthenticatedCaller} da camada de aplicação. É o único
 * ponto que conhece o token — o domínio e os casos de uso recebem só o record.
 */
@RequestScoped
public class CallerResolver {

    static final String CLIENT_ID_CLAIM = "clientId";
    static final String CLIENT_ROLE = "CLIENT";

    @Inject
    JsonWebToken jwt;

    public AuthenticatedCaller resolve() {
        Object claim = jwt.getClaim(CLIENT_ID_CLAIM);
        if (claim == null) {
            // Token válido e com papel CLIENT, mas sem a identidade: não é um token emitido pelo Lambda.
            throw new ForbiddenException("Token sem identificação do cliente");
        }
        return new AuthenticatedCaller(toLong(claim), CLIENT_ROLE);
    }

    private static Long toLong(Object claim) {
        if (claim instanceof Number number) {
            return number.longValue();
        }
        try {
            return Long.parseLong(claim.toString());
        } catch (NumberFormatException e) {
            throw new ForbiddenException("Token com identificação de cliente inválida");
        }
    }
}
