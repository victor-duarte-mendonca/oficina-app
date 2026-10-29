package br.com.oficina.testsupport;

import io.smallrye.jwt.build.Jwt;
import java.util.Set;

/**
 * Emite JWTs reais (RS256, chave de {@code test-keys/}) como o Lambda e o {@code AuthService}
 * fazem. Ao contrário de {@code @TestSecurity}, passam pela validação de assinatura, emissor e
 * claims — que é o caminho do token do cliente em produção.
 */
public final class TestTokens {

    private TestTokens() {
    }

    public static String client(long clientId) {
        return Jwt.issuer("oficina-api")
            .subject("cliente-" + clientId)
            .groups(Set.of("CLIENT"))
            .claim("clientId", clientId)
            .expiresIn(3600)
            .sign();
    }

    /** CLIENT sem o claim {@code clientId}: não é um token emitido pelo Lambda. */
    public static String clientWithoutIdentity() {
        return Jwt.issuer("oficina-api")
            .subject("cliente-sem-id")
            .groups(Set.of("CLIENT"))
            .expiresIn(3600)
            .sign();
    }

    public static String staff(String role) {
        return Jwt.issuer("oficina-api")
            .subject("staff-" + role.toLowerCase())
            .groups(Set.of(role))
            .expiresIn(3600)
            .sign();
    }
}
