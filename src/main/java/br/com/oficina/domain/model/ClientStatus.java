package br.com.oficina.domain.model;

/**
 * Status de um cliente da oficina.
 *
 * - ACTIVE: cliente ativo, pode autenticar e contratar serviços.
 * - INACTIVE: cadastro desativado (voluntariamente ou por inatividade).
 * - BLOCKED: impedido de autenticar (inadimplência, fraude, etc.).
 */
public enum ClientStatus {
    ACTIVE,
    INACTIVE,
    BLOCKED;

    /**
     * Regra de negócio: apenas clientes ACTIVE podem autenticar.
     * Usada pelo Lambda de autenticação e pela aplicação.
     */
    public boolean canAuthenticate() {
        return this == ACTIVE;
    }
}
