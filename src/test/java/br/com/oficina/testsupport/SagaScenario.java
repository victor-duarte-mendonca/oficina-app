package br.com.oficina.testsupport;

import br.com.oficina.application.dto.SagaViewDto;
import br.com.oficina.application.dto.StatusHistoryEntryDto;
import br.com.oficina.application.dto.WorkOrderCreateDto;
import br.com.oficina.application.dto.WorkOrderPartDto;
import br.com.oficina.application.dto.WorkOrderServiceDto;
import br.com.oficina.application.messaging.InboundMessage;
import br.com.oficina.application.messaging.MessageKind;
import br.com.oficina.application.ports.in.ChangeWorkOrderStatusUseCase;
import br.com.oficina.application.ports.in.CreateWorkOrderUseCase;
import br.com.oficina.application.ports.in.HandleSagaMessageUseCase;
import br.com.oficina.application.ports.in.ListWorkOrdersUseCase;
import br.com.oficina.application.ports.in.SagaAdministrationUseCase;
import br.com.oficina.application.ports.in.WorkOrderHistoryUseCase;
import br.com.oficina.application.ports.out.ClientRepositoryPort;
import br.com.oficina.application.ports.out.PartRepositoryPort;
import br.com.oficina.application.ports.out.ServiceItemRepositoryPort;
import br.com.oficina.application.ports.out.VehicleRepositoryPort;
import br.com.oficina.domain.model.Client;
import br.com.oficina.domain.model.ClientType;
import br.com.oficina.domain.model.Part;
import br.com.oficina.domain.model.ServiceItem;
import br.com.oficina.domain.model.Vehicle;
import br.com.oficina.domain.model.WorkOrderStatus;
import br.com.oficina.infrastructure.persistence.OutboxMessageEntity;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.arc.Arc;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Cenário de Saga sobre a aplicação real (Quarkus + PostgreSQL do teste): abre uma OS, faz o papel do
 * Billing e do Execution — lê os comandos que o OS publicou no outbox e devolve as respostas pela porta
 * de entrada do consumidor, como chegariam pelo SQS — e consulta o resultado.
 *
 * <p>Usado pelos testes de integração e pelos passos do BDD; os beans vêm do container do Quarkus, o
 * que permite instanciá-lo também onde não há injeção (passos do Cucumber).</p>
 */
public final class SagaScenario {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final CreateWorkOrderUseCase createWorkOrder = bean(CreateWorkOrderUseCase.class);
    private final ChangeWorkOrderStatusUseCase changeStatus = bean(ChangeWorkOrderStatusUseCase.class);
    private final ListWorkOrdersUseCase workOrders = bean(ListWorkOrdersUseCase.class);
    private final HandleSagaMessageUseCase replies = bean(HandleSagaMessageUseCase.class);
    private final SagaAdministrationUseCase sagas = bean(SagaAdministrationUseCase.class);
    private final WorkOrderHistoryUseCase history = bean(WorkOrderHistoryUseCase.class);
    private final ClientRepositoryPort clients = bean(ClientRepositoryPort.class);
    private final VehicleRepositoryPort vehicles = bean(VehicleRepositoryPort.class);
    private final PartRepositoryPort parts = bean(PartRepositoryPort.class);
    private final ServiceItemRepositoryPort serviceItems = bean(ServiceItemRepositoryPort.class);
    private final EntityManager em = bean(EntityManager.class);

    private Long workOrderId;
    private Long partId;

    /** Comando publicado pelo OS, como o participante o receberia. */
    public record SentCommand(String type, JsonNode payload) {
        public String text(String field) {
            return payload.get(field).asText();
        }
    }

    /** Abre uma OS com um serviço e peças do catálogo e inicia o diagnóstico. */
    public SagaScenario openWorkOrderInDiagnosis(BigDecimal servicePrice, int partQuantity, BigDecimal partPrice,
                                                 int initialStock) {
        String document = TestDocuments.cpf();
        inNewTransaction(() -> {
            Client client = clients.save(new Client("Cliente " + document, document, ClientType.PF,
                document + "@x.com", null));
            Vehicle vehicle = vehicles.save(new Vehicle(TestDocuments.plate(), "Fiat", "Argo", 2021, client.getId()));
            partId = parts.save(new Part("Pastilha " + document, null, partPrice, initialStock, "UN")).getId();
            Long serviceId = serviceItems.save(new ServiceItem("Troca de pastilhas " + document, null, servicePrice, 60))
                .getId();
            workOrderId = createWorkOrder.create(new WorkOrderCreateDto(document, vehicle.getId(), "barulho no freio",
                List.of(new WorkOrderServiceDto(serviceId, null)),
                List.of(new WorkOrderPartDto(partId, partQuantity)))).id();
            return null;
        });
        changeStatus.startDiagnosis(workOrderId);
        return this;
    }

    // ===== Ações da oficina =====

    public void sendForApproval() {
        changeStatus.sendForApproval(workOrderId);
    }

    public void cancel() {
        changeStatus.cancel(workOrderId);
    }

    public void retryCompensation() {
        sagas.retryCompensation(sagaId());
    }

    // ===== Papel dos participantes =====

    public HandleSagaMessageUseCase.Result reply(String type, Map<String, Object> payload) {
        return replies.handle(InboundMessage.of(UUID.randomUUID(), type, MessageKind.EVENT, sagaId(), workOrderId,
            payload));
    }

    public HandleSagaMessageUseCase.Result replyWith(InboundMessage message) {
        return replies.handle(message);
    }

    /** Comandos publicados para esta OS, na ordem de publicação. */
    public List<SentCommand> sentCommands() {
        return inNewTransaction(() -> em.createQuery(
                "FROM OutboxMessageEntity o WHERE o.messageGroupId = :group ORDER BY o.createdAt, o.id",
                OutboxMessageEntity.class)
            .setParameter("group", "wo-" + workOrderId)
            .getResultStream()
            .map(SagaScenario::toCommand)
            .toList());
    }

    public SentCommand lastCommand() {
        List<SentCommand> commands = sentCommands();
        return commands.get(commands.size() - 1);
    }

    public List<String> sentCommandTypes() {
        return sentCommands().stream().map(SentCommand::type).toList();
    }

    // ===== Consultas =====

    public Long workOrderId() {
        return workOrderId;
    }

    public UUID sagaId() {
        return saga().id();
    }

    public SagaViewDto saga() {
        return inNewTransaction(() -> sagas.findLatestForWorkOrder(workOrderId));
    }

    public WorkOrderStatus status() {
        return inNewTransaction(() -> workOrders.findById(workOrderId).status());
    }

    public int stock() {
        return inNewTransaction(() -> parts.fetchById(partId).orElseThrow().getStockQuantity());
    }

    public List<StatusHistoryEntryDto> history() {
        return inNewTransaction(() -> history.history(workOrderId));
    }

    /** Força o vencimento do prazo do passo atual (o relógio da aplicação é o do sistema). */
    public void expireCurrentStep() {
        UUID sagaId = sagaId();
        inNewTransaction(() -> em.createNativeQuery("UPDATE saga_instance SET deadline_at = :past WHERE id = :id")
            .setParameter("past", OffsetDateTime.now().minusMinutes(1))
            .setParameter("id", sagaId)
            .executeUpdate());
    }

    public List<UUID> overdueSagas() {
        return inNewTransaction(() -> sagas.overdueIds(100));
    }

    public void runTimeoutJobFor(UUID sagaId) {
        sagas.expire(sagaId);
    }

    /**
     * Leitura numa transação nova: fora dela, a sessão do contexto de requisição do teste devolveria
     * entidades em cache, já desatualizadas pelas transações dos casos de uso.
     */
    private static <T> T inNewTransaction(Supplier<T> work) {
        return QuarkusTransaction.requiringNew().call(work::get);
    }

    private static SentCommand toCommand(OutboxMessageEntity message) {
        try {
            return new SentCommand(message.getMessageType(), JSON.readTree(message.getPayload()).get("payload"));
        } catch (Exception e) {
            throw new IllegalStateException("Envelope inválido no outbox: " + message.getId(), e);
        }
    }

    private static <T> T bean(Class<T> type) {
        return Arc.container().instance(type).get();
    }
}
