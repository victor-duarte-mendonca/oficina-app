package br.com.oficina.domain.saga;

import br.com.oficina.domain.exception.BusinessException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Agregado da Saga de uma OS (spec 04): orçamento → aprovação → pagamento → execução, com compensação
 * em ordem inversa em qualquer falha.
 *
 * <p><b>State pattern:</b> cada estado tem um {@link SagaStateHandler} que decide o que um evento ou um
 * timeout significa ali. Este agregado guarda o estado, a pilha de compensações e os contadores, e
 * oferece aos handlers as operações que preservam as invariantes:</p>
 * <ul>
 *   <li>um passo por vez — só existe um comando aguardando resposta;</li>
 *   <li>todo efeito externo concluído (orçamento, pagamento, execução) tem a sua compensação empilhada;</li>
 *   <li>a Saga nunca termina {@code CANCELLED} com compensação pendente — ou desfaz tudo, ou para em
 *       {@code COMPENSATION_PENDING}.</li>
 * </ul>
 *
 * <p>Domínio puro: sem framework, sem relógio do sistema, sem I/O. Quem envia os comandos e aplica o
 * efeito na OS é a camada de aplicação, a partir do {@link SagaDecision} devolvido.</p>
 */
public final class WorkOrderSaga {

    private static final Map<SagaState, SagaStateHandler> HANDLERS = handlers();

    private final UUID id;
    private final Long workOrderId;
    private SagaState state;
    /** Topo = próxima compensação a executar. */
    private final Deque<Compensation> compensations = new ArrayDeque<>();
    private int stepAttempts;
    private int paymentAttempts;
    private String budgetId;
    private String paymentId;
    private boolean paymentCaptured;
    private OffsetDateTime deadlineAt;
    private String failureReason;
    private Long version;
    private OffsetDateTime createdAt;

    private WorkOrderSaga(UUID id, Long workOrderId) {
        this.id = Objects.requireNonNull(id);
        this.workOrderId = Objects.requireNonNull(workOrderId);
    }

    /** Nova Saga, ainda não iniciada: {@link #begin(SagaContext)} envia o primeiro comando. */
    public static WorkOrderSaga newFor(UUID id, Long workOrderId) {
        return new WorkOrderSaga(id, workOrderId);
    }

    /** Reconstrói a Saga persistida (uso exclusivo do adapter). {@code compensationsTopFirst}: topo primeiro. */
    public static WorkOrderSaga rehydrate(UUID id, Long workOrderId, SagaState state,
                                          List<Compensation> compensationsTopFirst, int stepAttempts,
                                          int paymentAttempts, String budgetId, String paymentId,
                                          boolean paymentCaptured, OffsetDateTime deadlineAt,
                                          String failureReason, Long version, OffsetDateTime createdAt) {
        WorkOrderSaga saga = new WorkOrderSaga(id, workOrderId);
        saga.state = state;
        compensationsTopFirst.forEach(saga.compensations::addLast);
        saga.stepAttempts = stepAttempts;
        saga.paymentAttempts = paymentAttempts;
        saga.budgetId = budgetId;
        saga.paymentId = paymentId;
        saga.paymentCaptured = paymentCaptured;
        saga.deadlineAt = deadlineAt;
        saga.failureReason = failureReason;
        saga.version = version;
        saga.createdAt = createdAt;
        return saga;
    }

    // ===== API pública (chamada pela aplicação) =====

    public SagaDecision begin(SagaContext ctx) {
        if (state != null) {
            throw new BusinessException("Saga " + id + " já foi iniciada");
        }
        createdAt = ctx.now();
        return advance(SagaState.BUDGET_REQUESTED, ctx.policy().stepReplyTimeout(), ctx,
            StepOutcome.STARTED, OrderEffect.NONE, new SagaCommand.GenerateBudget());
    }

    public SagaDecision on(SagaEvent event, SagaContext ctx) {
        Objects.requireNonNull(event);
        return handler().on(this, event, ctx);
    }

    /** Chamado pelo job de prazos: sem efeito se o prazo do passo atual ainda não venceu. */
    public SagaDecision onTimeout(SagaContext ctx) {
        if (!isOverdue(ctx.now())) {
            return SagaDecision.ignored("prazo do passo ainda não venceu");
        }
        return handler().onTimeout(this, ctx);
    }

    /** Cancelamento manual da OS com Saga ativa (spec 04 passo C): compensa o que já foi feito. */
    public SagaDecision requestCancellation(String reason, SagaContext ctx) {
        if (state == null || state.isTerminal() || state.isCompensating()) {
            throw new BusinessException("A Saga da OS não admite cancelamento no estado " + state);
        }
        return compensate(reason, ctx);
    }

    /** Nova tentativa manual depois de {@code COMPENSATION_PENDING} (ex.: estorno recusado por falta de saldo). */
    public SagaDecision retryCompensation(SagaContext ctx) {
        if (state != SagaState.COMPENSATION_PENDING) {
            throw new BusinessException("Só há compensação a refazer em COMPENSATION_PENDING; estado atual: " + state);
        }
        state = SagaState.COMPENSATING;
        return compensateNext(ctx, StepOutcome.RETRIED);
    }

    public boolean isOverdue(OffsetDateTime now) {
        return deadlineAt != null && !now.isBefore(deadlineAt);
    }

    // ===== Operações para os handlers (preservam as invariantes) =====

    SagaDecision advance(SagaState next, Duration timeout, SagaContext ctx, StepOutcome outcome,
                         OrderEffect effect, SagaCommand... commands) {
        state = next;
        stepAttempts = commands.length == 0 ? 0 : 1;
        deadlineAt = ctx.deadline(timeout);
        return new SagaDecision(outcome, List.of(commands), effect, null);
    }

    /** Reenvia o comando do passo atual (resposta técnica ausente). Seguro porque os participantes são idempotentes. */
    SagaDecision retry(SagaCommand command, Duration timeout, SagaContext ctx) {
        stepAttempts++;
        deadlineAt = ctx.deadline(timeout);
        return SagaDecision.of(StepOutcome.RETRIED, command, OrderEffect.NONE);
    }

    boolean canRetryStep(SagaPolicy policy) {
        return stepAttempts < policy.maxStepAttempts();
    }

    boolean hasPaymentAttemptsLeft(SagaPolicy policy) {
        return paymentAttempts < policy.maxPaymentAttempts();
    }

    int nextPaymentAttempt() {
        return ++paymentAttempts;
    }

    void recordBudget(String generatedBudgetId) {
        this.budgetId = generatedBudgetId;
    }

    void recordPayment(String requestedPaymentId) {
        this.paymentId = requestedPaymentId;
    }

    void capturePayment(String approvedPaymentId) {
        this.paymentId = approvedPaymentId;
        this.paymentCaptured = true;
    }

    void pushCompensation(Compensation compensation) {
        compensations.push(compensation);
    }

    /** Remove uma compensação que deixou de fazer sentido (ex.: a execução já falhou sozinha). */
    void dropCompensation(Compensation compensation) {
        compensations.remove(compensation);
    }

    boolean hasPendingCompensation(Compensation compensation) {
        return compensations.contains(compensation);
    }

    Optional<Compensation> nextCompensation() {
        return Optional.ofNullable(compensations.peek());
    }

    /** Entra em compensação; com a pilha vazia encerra direto em {@code CANCELLED}. */
    SagaDecision compensate(String reason, SagaContext ctx) {
        state = SagaState.COMPENSATING;
        failureReason = reason;
        return compensateNext(ctx, StepOutcome.COMPENSATING).withDetail(reason);
    }

    /** A compensação do topo foi confirmada: segue para a próxima ou encerra. */
    SagaDecision confirmCompensation(SagaContext ctx) {
        compensations.pop();
        state = SagaState.COMPENSATING;
        return compensateNext(ctx, StepOutcome.COMPENSATING);
    }

    /** Recusa recuperável da compensação: tenta de novo até o limite, depois exige intervenção. */
    SagaDecision retryCompensationOrPark(SagaContext ctx, String detail) {
        if (!canRetryStep(ctx.policy())) {
            return park(detail);
        }
        Compensation top = compensations.element();
        return retry(top.command(this, failureReason), ctx.policy().stepReplyTimeout(), ctx).withDetail(detail);
    }

    /** Compensação impossível de concluir sozinha: aguarda intervenção ({@code POST /admin/sagas/{id}/retry-compensation}). */
    SagaDecision park(String detail) {
        state = SagaState.COMPENSATION_PENDING;
        deadlineAt = null;
        return SagaDecision.of(StepOutcome.COMPENSATION_PENDING, OrderEffect.NONE, detail);
    }

    SagaDecision complete() {
        state = SagaState.COMPLETED;
        compensations.clear();
        deadlineAt = null;
        stepAttempts = 0;
        return SagaDecision.of(StepOutcome.COMPLETED, OrderEffect.COMPLETE, null);
    }

    /** Encerra sem nada a desfazer fora do OS: a OS só volta ao diagnóstico. */
    SagaDecision abort(String reason) {
        state = SagaState.CANCELLED;
        failureReason = reason;
        deadlineAt = null;
        stepAttempts = 0;
        return SagaDecision.of(StepOutcome.ABORTED, OrderEffect.REVERT_TO_DIAGNOSIS, reason);
    }

    /** Desfaz à parte um efeito que chegou depois do cancelamento (não entra na pilha nem muda o estado). */
    SagaDecision compensateOrphan(SagaCommand command, String detail) {
        return SagaDecision.of(StepOutcome.ORPHAN_COMPENSATED, command, OrderEffect.NONE).withDetail(detail);
    }

    private SagaDecision compensateNext(SagaContext ctx, StepOutcome outcome) {
        Optional<Compensation> next = nextCompensation();
        if (next.isEmpty()) {
            state = SagaState.CANCELLED;
            deadlineAt = null;
            stepAttempts = 0;
            return SagaDecision.of(StepOutcome.CANCELLED, OrderEffect.CANCEL, failureReason);
        }
        stepAttempts = 1;
        deadlineAt = ctx.deadline(ctx.policy().stepReplyTimeout());
        return SagaDecision.of(outcome, next.get().command(this, failureReason), OrderEffect.NONE);
    }

    private SagaStateHandler handler() {
        if (state == null) {
            throw new BusinessException("Saga " + id + " ainda não foi iniciada");
        }
        return HANDLERS.get(state);
    }

    private static Map<SagaState, SagaStateHandler> handlers() {
        Map<SagaState, SagaStateHandler> map = new EnumMap<>(SagaState.class);
        map.put(SagaState.BUDGET_REQUESTED, new BudgetRequestedHandler());
        map.put(SagaState.AWAITING_APPROVAL, new AwaitingApprovalHandler());
        map.put(SagaState.PAYMENT_REQUESTED, new PaymentRequestedHandler());
        map.put(SagaState.AWAITING_PAYMENT, new AwaitingPaymentHandler());
        map.put(SagaState.EXECUTION_REQUESTED, new ExecutionRequestedHandler());
        map.put(SagaState.AWAITING_EXECUTION, new ExecutionInProgressHandler(false));
        map.put(SagaState.IN_EXECUTION, new ExecutionInProgressHandler(true));
        SagaStateHandler compensating = new CompensatingHandler();
        map.put(SagaState.COMPENSATING, compensating);
        map.put(SagaState.COMPENSATION_PENDING, compensating);
        SagaStateHandler terminal = new TerminalHandler();
        map.put(SagaState.COMPLETED, terminal);
        map.put(SagaState.CANCELLED, terminal);
        return map;
    }

    // ===== Leitura =====

    public UUID id() { return id; }
    public Long workOrderId() { return workOrderId; }
    public SagaState state() { return state; }
    /** Compensações pendentes, topo primeiro. */
    public List<Compensation> pendingCompensations() { return List.copyOf(compensations); }
    public int stepAttempts() { return stepAttempts; }
    public int paymentAttempts() { return paymentAttempts; }
    public String budgetId() { return budgetId; }
    public String paymentId() { return paymentId; }
    public boolean paymentCaptured() { return paymentCaptured; }
    public OffsetDateTime deadlineAt() { return deadlineAt; }
    public String failureReason() { return failureReason; }
    public Long version() { return version; }
    public OffsetDateTime createdAt() { return createdAt; }
}
