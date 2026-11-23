package br.com.oficina.application;

/**
 * A operação deixou de pertencer a este serviço na Fase 4 (ex.: a aprovação do orçamento passou para o
 * Billing). Responde 410 Gone com a rota substituta, em vez de executar um caminho que contornaria a
 * Saga.
 */
public class OperationMovedException extends RuntimeException {

    private final String replacement;

    public OperationMovedException(String operation, String replacement) {
        super(operation + " foi movida para outro serviço. Use: " + replacement);
        this.replacement = replacement;
    }

    public String replacement() {
        return replacement;
    }
}
