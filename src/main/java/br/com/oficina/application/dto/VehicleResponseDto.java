package br.com.oficina.application.dto;

import br.com.oficina.domain.model.Vehicle;
import java.time.OffsetDateTime;

public record VehicleResponseDto(
    Long id,
    String licensePlate,
    String brand,
    String model,
    Integer productionYear,
    Long clientId,
    String clientName,
    OffsetDateTime createdAt
) {
    public static VehicleResponseDto from(Vehicle v) {
        return new VehicleResponseDto(
            v.getId(), v.getLicensePlate(), v.getBrand(), v.getModel(),
            v.getProductionYear(), v.getClientId(), v.getClientName(), v.getCreatedAt()
        );
    }
}
