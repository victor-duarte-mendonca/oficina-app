package br.com.oficina.application.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Abertura de OS pelo próprio cliente. Só veículo e relato do problema: serviços e peças são
 * definidos pela oficina no diagnóstico. Aceitá-los aqui deixaria o cliente baixar estoque e
 * fixar preço.
 */
public record ClientWorkOrderRequestDto(
    @NotNull(message = "ID do veículo é obrigatório") Long vehicleId,
    @Size(max = 500, message = "Observações devem ter no máximo 500 caracteres") String notes
) {}
