package br.com.oficina.domain.model;

import java.time.OffsetDateTime;

/**
 * Veículo da oficina — modelo de domínio puro, sem dependência de framework de
 * persistência. Referencia o cliente proprietário por identidade ({@code clientId}),
 * mantendo o nome ({@code clientName}) como projeção de leitura para exibição.
 * A tradução para a tabela {@code vehicles} é feita pelo
 * {@code VehicleEntity}/{@code VehicleMapper} na camada de infraestrutura.
 */
public class Vehicle {

    private Long id;
    private String licensePlate;
    private String brand;
    private String model;
    private Integer productionYear;
    private Long clientId;
    private String clientName;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;

    public Vehicle(String licensePlate, String brand, String model, Integer productionYear, Long clientId) {
        this.licensePlate = licensePlate;
        this.brand = brand;
        this.model = model;
        this.productionYear = productionYear;
        this.clientId = clientId;
    }

    private Vehicle() {
        // Reconstrução via rehydrate().
    }

    /** Reconstrói o veículo a partir da persistência (uso exclusivo do mapper). */
    public static Vehicle rehydrate(Long id, String licensePlate, String brand, String model,
                                    Integer productionYear, Long clientId, String clientName,
                                    OffsetDateTime createdAt, OffsetDateTime updatedAt) {
        Vehicle v = new Vehicle();
        v.id = id;
        v.licensePlate = licensePlate;
        v.brand = brand;
        v.model = model;
        v.productionYear = productionYear;
        v.clientId = clientId;
        v.clientName = clientName;
        v.createdAt = createdAt;
        v.updatedAt = updatedAt;
        return v;
    }

    public void update(String licensePlate, String brand, String model, Integer productionYear, Long clientId) {
        this.licensePlate = licensePlate;
        this.brand = brand;
        this.model = model;
        this.productionYear = productionYear;
        this.clientId = clientId;
    }

    public Long getId() { return id; }
    public String getLicensePlate() { return licensePlate; }
    public String getBrand() { return brand; }
    public String getModel() { return model; }
    public Integer getProductionYear() { return productionYear; }
    public Long getClientId() { return clientId; }
    public String getClientName() { return clientName; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
}
