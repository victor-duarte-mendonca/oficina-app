package br.com.oficina.application.security;

import java.util.Objects;

/**
 * Identidade do chamador, já traduzida pelo adapter REST a partir do token. Mantém a camada de
 * aplicação e o domínio sem conhecer {@code JsonWebToken}, {@code SecurityContext} ou qualquer
 * tipo do Quarkus — é essa fronteira que permite testar os casos de uso sem {@code @QuarkusTest}.
 *
 * @param clientId id do cliente autenticado (claim {@code clientId} emitido pelo Lambda)
 * @param role     papel do chamador (hoje, {@code CLIENT})
 */
public record AuthenticatedCaller(Long clientId, String role) {

    public AuthenticatedCaller {
        Objects.requireNonNull(clientId, "clientId é obrigatório");
        Objects.requireNonNull(role, "role é obrigatório");
    }
}
