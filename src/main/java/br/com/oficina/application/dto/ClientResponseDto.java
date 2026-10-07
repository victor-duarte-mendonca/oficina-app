package br.com.oficina.application.dto;

import br.com.oficina.domain.model.Client;
import br.com.oficina.domain.model.ClientType;
import java.time.OffsetDateTime;

public record ClientResponseDto(
    Long id,
    String name,
    String cpfCnpj,
    ClientType clientType,
    String email,
    String phone,
    OffsetDateTime createdAt
) {
    public static ClientResponseDto from(Client c) {
        return new ClientResponseDto(
            c.getId(), c.getName(), c.getCpfCnpj(), c.getClientType(),
            c.getEmail(), c.getPhone(), c.getCreatedAt()
        );
    }
}
