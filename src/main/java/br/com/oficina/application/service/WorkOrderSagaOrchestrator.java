package br.com.oficina.application.service;

import br.com.oficina.application.messaging.InboundMessage;
import br.com.oficina.application.messaging.InvalidMessageException;
import br.com.oficina.application.messaging.MessageKind;
import br.com.oficina.application.messaging.SagaCommandMessageFactory;
import br.com.oficina.application.messaging.SagaEventTranslator;
import br.com.oficina.application.ports.in.HandleSagaMessageUseCase;
import br.com.oficina.application.ports.out.InboxPort;
import br.com.oficina.application.ports.out.OutboxPort;
import br.com.oficina.application.ports.out.SagaMetricsPort;
import br.com.oficina.application.ports.out.SagaRepositoryPort;
import br.com.oficina.application.ports.out.WorkOrderRepositoryPort;
import br.com.oficina.domain.exception.BusinessException;
import br.com.oficina.domain.exception.ResourceNotFoundException;
import br.com.oficina.domain.model.WorkOrder;
import br.com.oficina.domain.saga.SagaContext;
import br.com.oficina.domain.saga.SagaDecision;
import br.com.oficina.domain.saga.SagaEvent;
import br.com.oficina.domain.saga.SagaPolicy;
import br.com.oficina.domain.saga.SagaStepRecord;
import br.com.oficina.domain.saga.StepOutcome;
import br.com.oficina.domain.saga.WorkOrderSaga;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jboss.logging.Logger;

/**
 * Orquestrador da Saga da OS (spec 04). Recebe o gatilho (início, resposta de participante, timeout,
 * cancelamento), pede a decisão ao agregado {@link WorkOrderSaga} e a executa — tudo numa única
 * transação local: transição da OS + estado da Saga + log do passo + comandos no outbox.
 *
 * <p>Por isso a Saga nunca fica dessincronizada da OS, e nenhum comando sai sem a mudança de estado
 * que o justifica. A decisão em si (o que fazer em cada estado) é do domínio; aqui só se executa.</p>
 */
@ApplicationScoped
public class WorkOrderSagaOrchestrator implements HandleSagaMessageUseCase {

    static final String CONSUMER = "os-saga";
    private static final int MAX_DETAIL = 500;
    private static final Set<StepOutcome> CLOSING = EnumSet.of(StepOutcome.COMPLETED, StepOutcome.CANCELLED,
        StepOutcome.ABORTED);
    private static final Logger LOG = Logger.getLogger(WorkOrderSagaOrchestrator.class);

    private final SagaRepositoryPort sagas;
    private final WorkOrderRepositoryPort workOrders;
    private final InboxPort inbox;
    private final OutboxPort outbox;
    private final SagaCommandMessageFactory messages;
    private final SagaEventTranslator translator;
    private final SagaOrderEffects effects;
    private final SagaPolicy policy;
    private final Clock clock;
    private final SagaMetricsPort metrics;

    public WorkOrderSagaOrchestrator(SagaRepositoryPort sagas, WorkOrderRepositoryPort workOrders,
                                     InboxPort inbox, OutboxPort outbox, SagaCommandMessageFactory messages,
                                     SagaEventTranslator translator, SagaOrderEffects effects,
                                     SagaPolicy policy, Clock clock, SagaMetricsPort metrics) {
        this.sagas = sagas;
        this.workOrders = workOrders;
        this.inbox = inbox;
        this.outbox = outbox;
        this.messages = messages;
        this.translator = translator;
        this.effects = effects;
        this.policy = policy;
        this.clock = clock;
        this.metrics = metrics;
    }

    // ===== Gatilhos internos (chamados pelo fluxo de aprovação, dentro da transação do caso de uso) =====

    public boolean hasActiveSaga(Long workOrderId) {
        return sagas.findActiveByWorkOrderId(workOrderId).isPresent();
    }

    public boolean hasSaga(Long workOrderId) {
        return sagas.findLatestByWorkOrderId(workOrderId).isPresent();
    }

    @Transactional
    public WorkOrderSaga start(WorkOrder workOrder) {
        if (hasActiveSaga(workOrder.getId())) {
            throw new BusinessException("A OS " + workOrder.getOrderNumber() + " já tem uma Saga em andamento");
        }
        WorkOrderSaga saga = WorkOrderSaga.newFor(UUID.randomUUID(), workOrder.getId());
        SagaContext ctx = context();
        SagaDecision decision = saga.begin(ctx);
        metrics.sagaStarted();
        return execute(saga, decision, workOrder, "SagaStarted", null, ctx);
    }

    @Transactional
    public void requestCancellation(WorkOrder workOrder, String reason) {
        WorkOrderSaga saga = sagas.findActiveByWorkOrderId(workOrder.getId())
            .orElseThrow(() -> new BusinessException("A OS " + workOrder.getOrderNumber() + " não tem Saga ativa"));
        SagaContext ctx = context();
        execute(saga, saga.requestCancellation(reason, ctx), workOrder, "CancellationRequested", null, ctx);
    }

    /** Informativo ao Execution: diagnóstico aberto (não inicia nem move a Saga). */
    @Transactional
    public void announceDiagnosis(WorkOrder workOrder) {
        outbox.enqueue(messages.diagnosisStarted(workOrder, now()));
    }

    // ===== Gatilhos externos =====

    @Override
    @Transactional
    public Result handle(InboundMessage message) {
        if (!inbox.markProcessed(message.messageId(), CONSUMER)) {
            LOG.debugf("Mensagem %s (%s) repetida; ignorada", message.messageId(), message.type());
            return Result.DUPLICATE;
        }
        if (message.kind() == MessageKind.INFO) {
            LOG.debugf("Informativo %s da OS %s recebido", message.type(), message.workOrderId());
            return Result.SKIPPED;
        }
        Optional<SagaEvent> event = translator.translate(message);
        if (event.isEmpty()) {
            LOG.warnf("Tipo de mensagem desconhecido para a Saga: %s (mensagem %s)", message.type(), message.messageId());
            return Result.SKIPPED;
        }
        if (message.sagaId() == null) {
            throw new InvalidMessageException("Evento de Saga sem sagaId: " + message.type());
        }
        Optional<WorkOrderSaga> saga = sagas.findById(message.sagaId());
        if (saga.isEmpty()) {
            LOG.warnf("Saga %s inexistente para o evento %s", message.sagaId(), message.type());
            return Result.SKIPPED;
        }
        WorkOrderSaga current = saga.get();
        SagaContext ctx = context();
        execute(current, current.on(event.get(), ctx), loadWorkOrder(current), message.type(), message.messageId(), ctx);
        return Result.PROCESSED;
    }

    /** Prazo vencido de uma Saga. Cada Saga na sua própria transação: uma falha não trava as outras. */
    @Transactional
    public void expire(UUID sagaId) {
        sagas.findById(sagaId).ifPresent(saga -> {
            SagaContext ctx = context();
            SagaDecision decision = saga.onTimeout(ctx);
            if (!decision.isIgnored()) {
                execute(saga, decision, loadWorkOrder(saga), "Timeout", null, ctx);
            }
        });
    }

    @Transactional
    public WorkOrderSaga retryCompensation(UUID sagaId) {
        WorkOrderSaga saga = sagas.findById(sagaId)
            .orElseThrow(() -> new ResourceNotFoundException("Saga não encontrada: " + sagaId));
        SagaContext ctx = context();
        return execute(saga, saga.retryCompensation(ctx), loadWorkOrder(saga), "CompensationRetryRequested", null, ctx);
    }

    // ===== Execução da decisão =====

    private WorkOrderSaga execute(WorkOrderSaga saga, SagaDecision decision, WorkOrder workOrder,
                                  String step, UUID causationId, SagaContext ctx) {
        WorkOrder current = effects.apply(decision.effect(), workOrder, decision.detail());
        WorkOrderSaga saved = sagas.save(saga);
        sagas.appendStep(new SagaStepRecord(saved.id(), step, causationId, decision.outcome(), saved.state(),
            truncate(decision.detail()), ctx.now()));
        decision.commands().forEach(command ->
            outbox.enqueue(messages.commandFor(command, saved, current, causationId, ctx.now())));
        metrics.stepRecorded(decision.outcome());
        if (CLOSING.contains(decision.outcome())) {
            metrics.sagaClosed(saved.state(), Duration.between(saved.createdAt(), ctx.now()));
        }
        LOG.infof("Saga %s da OS %s: %s -> %s (%s)%s", saved.id(), current.getOrderNumber(), step,
            saved.state(), decision.outcome(), decision.detail() == null ? "" : " — " + decision.detail());
        return saved;
    }

    private WorkOrder loadWorkOrder(WorkOrderSaga saga) {
        return workOrders.fetchById(saga.workOrderId())
            .orElseThrow(() -> new ResourceNotFoundException("Ordem de Serviço", saga.workOrderId()));
    }

    private SagaContext context() {
        return new SagaContext(policy, now());
    }

    private OffsetDateTime now() {
        return OffsetDateTime.now(clock);
    }

    private static String truncate(String detail) {
        return detail == null || detail.length() <= MAX_DETAIL ? detail : detail.substring(0, MAX_DETAIL);
    }
}
