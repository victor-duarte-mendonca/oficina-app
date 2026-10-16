package br.com.oficina.interfaces.rest;

import br.com.oficina.application.dto.ApprovalDecisionDto;
import br.com.oficina.application.dto.ClientResponseDto;
import br.com.oficina.application.dto.ClientWorkOrderRequestDto;
import br.com.oficina.application.dto.VehicleResponseDto;
import br.com.oficina.application.dto.WorkOrderResponseDto;
import br.com.oficina.application.ports.in.ClientSelfServiceUseCase;
import br.com.oficina.application.security.AuthenticatedCaller;
import jakarta.annotation.security.RolesAllowed;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.net.URI;
import java.util.List;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/**
 * Área do cliente autenticado por CPF (token emitido pelo Lambda, claim {@code clientId}). O id do
 * cliente vem sempre do token, nunca do path: não existe rota {@code /me/{clientId}}.
 */
@Path("/me")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed("CLIENT")
@SecurityRequirement(name = "bearerAuth")
@Tag(name = "Área do cliente", description = "Rotas do cliente autenticado por CPF")
public class MeResource {

    private final ClientSelfServiceUseCase selfService;
    private final CallerResolver callers;

    public MeResource(ClientSelfServiceUseCase selfService, CallerResolver callers) {
        this.selfService = selfService;
        this.callers = callers;
    }

    @GET
    @Operation(summary = "Dados do próprio cadastro")
    public ClientResponseDto profile() {
        return selfService.profile(callers.resolve());
    }

    @GET
    @Path("/vehicles")
    @Operation(summary = "Veículos do cliente")
    public List<VehicleResponseDto> vehicles() {
        return selfService.vehicles(callers.resolve());
    }

    @GET
    @Path("/work-orders")
    @Operation(summary = "OS do cliente (paginado, da mais recente à mais antiga)",
               description = "Inclui as encerradas. Total, ignorando a paginação, no cabeçalho X-Total-Count.")
    public Response listWorkOrders(@QueryParam("page") @DefaultValue("0") int page,
                                   @QueryParam("size") @DefaultValue("20") int size) {
        AuthenticatedCaller caller = callers.resolve();
        return Response.ok(selfService.listWorkOrders(caller, page, size))
            .header(WorkOrderResource.TOTAL_COUNT_HEADER, selfService.countWorkOrders(caller))
            .build();
    }

    @GET
    @Path("/work-orders/{id}")
    @Operation(summary = "Detalhe de uma OS do cliente",
               description = "404 (e não 403) se a OS pertencer a outro cliente, para não revelar que o id existe.")
    public WorkOrderResponseDto findWorkOrder(@PathParam("id") Long id) {
        return selfService.findWorkOrder(callers.resolve(), id);
    }

    @POST
    @Path("/work-orders")
    @Operation(summary = "Abrir OS para um veículo do próprio cliente",
               description = "O veículo precisa pertencer ao cliente autenticado (404 caso contrário).")
    public Response openWorkOrder(@Valid @NotNull(message = "Corpo da requisição é obrigatório")
                                  ClientWorkOrderRequestDto request) {
        WorkOrderResponseDto created = selfService.openWorkOrder(callers.resolve(), request);
        return Response.created(URI.create("/me/work-orders/" + created.id())).entity(created).build();
    }

    @POST
    @Path("/work-orders/{id}/approval")
    @Operation(summary = "Aprovar ou recusar o orçamento da própria OS",
               description = "Só vale em AWAITING_APPROVAL. Corpo: {\"decision\": \"APPROVE\" | \"REJECT\"}.")
    public WorkOrderResponseDto decideApproval(@PathParam("id") Long id,
                                               @Valid @NotNull(message = "Corpo da requisição é obrigatório")
                                               ApprovalDecisionDto decision) {
        return selfService.decideApproval(callers.resolve(), id, decision);
    }
}
