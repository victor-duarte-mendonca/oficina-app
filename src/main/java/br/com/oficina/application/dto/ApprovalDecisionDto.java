package br.com.oficina.application.dto;

import jakarta.validation.constraints.NotNull;

/** Decisão do cliente autenticado sobre o orçamento da própria OS. */
public record ApprovalDecisionDto(
    @NotNull(message = "A decisão é obrigatória (APPROVE ou REJECT)") Decision decision
) {

    public enum Decision { APPROVE, REJECT }
}
