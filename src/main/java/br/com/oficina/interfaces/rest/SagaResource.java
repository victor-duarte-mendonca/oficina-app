package br.com.oficina.interfaces.rest;

import br.com.oficina.application.dto.SagaViewDto;
import br.com.oficina.application.ports.in.SagaAdministrationUseCase;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.util.UUID;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

@Path("/admin/sagas")
@Produces(MediaType.APPLICATION_JSON)
@RolesAllowed({"ADMIN", "MECHANIC"})
@SecurityRequirement(name = "bearerAuth")
@Tag(name = "Saga", description = "Acompanhamento e operação da Saga de ordens de serviço (Fase 4)")
public class SagaResource {

    private final SagaAdministrationUseCase sagas;

    public SagaResource(SagaAdministrationUseCase sagas) {
        this.sagas = sagas;
    }

    @GET
    @Path("/{id}")
    @Operation(summary = "Estado e log de passos de uma Saga")
    public SagaViewDto findById(@PathParam("id") UUID id) {
        return sagas.findById(id);
    }

    @POST
    @Path("/{id}/retry-compensation")
    @RolesAllowed("ADMIN")
    @Operation(summary = "Refazer a compensação parada",
               description = "Só em COMPENSATION_PENDING (ex.: estorno recusado por falta de saldo). "
                             + "Reenvia a compensação do topo da pilha.")
    public SagaViewDto retryCompensation(@PathParam("id") UUID id) {
        return sagas.retryCompensation(id);
    }
}
