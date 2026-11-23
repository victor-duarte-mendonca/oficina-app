-- ===================================================
-- V7: Fase 4 — Saga orquestrada, outbox/inbox e histórico de status (spec 02 §2).
--
-- Somente aditiva: a versão anterior da aplicação continua funcionando contra este schema
-- (pré-condição do rollback automático do deploy). Nada é removido aqui; a limpeza do token de
-- aprovação, que passa a pertencer ao Billing, fica para uma migration posterior.
-- ===================================================

-- ===================================================
-- 1. Novos status da OS e o instante de entrada na fila de execução
-- ===================================================

ALTER TABLE work_orders DROP CONSTRAINT ck_work_orders_status;
ALTER TABLE work_orders
    ADD CONSTRAINT ck_work_orders_status CHECK (status IN (
        'RECEIVED', 'IN_DIAGNOSIS', 'AWAITING_APPROVAL', 'AWAITING_PAYMENT', 'AWAITING_EXECUTION',
        'IN_EXECUTION', 'FINISHED', 'DELIVERED', 'CANCELLED'
    ));

ALTER TABLE work_orders ADD COLUMN IF NOT EXISTS queued_for_execution_at TIMESTAMPTZ;

-- ===================================================
-- 2. Histórico de status ("consulta de status e histórico", R1)
-- ===================================================

CREATE TABLE work_order_status_history (
    id              BIGSERIAL PRIMARY KEY,
    work_order_id   BIGINT       NOT NULL REFERENCES work_orders (id),
    from_status     VARCHAR(25),
    to_status       VARCHAR(25)  NOT NULL,
    source          VARCHAR(10)  NOT NULL,
    actor           VARCHAR(100),
    reason          VARCHAR(300),
    correlation_id  VARCHAR(128),
    occurred_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ck_status_history_source CHECK (source IN ('USER', 'SAGA', 'SYSTEM'))
);

CREATE INDEX idx_status_history_work_order ON work_order_status_history (work_order_id, occurred_at, id);

-- Backfill: reconstrói o histórico das OS existentes a partir dos instantes já registrados, para que a
-- consulta de histórico não comece vazia para quem estava no meio do fluxo da Fase 3.
INSERT INTO work_order_status_history (work_order_id, from_status, to_status, source, reason, occurred_at)
SELECT id, NULL, 'RECEIVED', 'SYSTEM', 'reconstruído na V7', created_at FROM work_orders
UNION ALL
SELECT id, 'RECEIVED', 'IN_DIAGNOSIS', 'SYSTEM', 'reconstruído na V7', diagnosis_started_at
  FROM work_orders WHERE diagnosis_started_at IS NOT NULL
UNION ALL
SELECT id, 'IN_DIAGNOSIS', 'AWAITING_APPROVAL', 'SYSTEM', 'reconstruído na V7', sent_for_approval_at
  FROM work_orders WHERE sent_for_approval_at IS NOT NULL
UNION ALL
SELECT id, 'AWAITING_APPROVAL', 'IN_EXECUTION', 'SYSTEM', 'reconstruído na V7', execution_started_at
  FROM work_orders WHERE execution_started_at IS NOT NULL
UNION ALL
SELECT id, 'IN_EXECUTION', 'FINISHED', 'SYSTEM', 'reconstruído na V7', finished_at
  FROM work_orders WHERE finished_at IS NOT NULL
UNION ALL
SELECT id, 'FINISHED', 'DELIVERED', 'SYSTEM', 'reconstruído na V7', delivered_at
  FROM work_orders WHERE delivered_at IS NOT NULL
UNION ALL
SELECT id, NULL, 'CANCELLED', 'SYSTEM', 'reconstruído na V7', cancelled_at
  FROM work_orders WHERE cancelled_at IS NOT NULL;

-- ===================================================
-- 3. Saga (estado do orquestrador) e log de passos (auditoria)
-- ===================================================

CREATE TABLE saga_instance (
    id                UUID PRIMARY KEY,
    work_order_id     BIGINT       NOT NULL REFERENCES work_orders (id),
    state             VARCHAR(30)  NOT NULL,
    -- Pilha de compensações pendentes, topo primeiro, separada por vírgula (ex.: 'REFUND_PAYMENT,VOID_BUDGET').
    compensations     VARCHAR(100) NOT NULL DEFAULT '',
    step_attempts     INTEGER      NOT NULL DEFAULT 0,
    payment_attempts  INTEGER      NOT NULL DEFAULT 0,
    budget_id         VARCHAR(64),
    payment_id        VARCHAR(64),
    payment_captured  BOOLEAN      NOT NULL DEFAULT FALSE,
    deadline_at       TIMESTAMPTZ,
    failure_reason    VARCHAR(300),
    version           BIGINT       NOT NULL DEFAULT 0,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ck_saga_state CHECK (state IN (
        'BUDGET_REQUESTED', 'AWAITING_APPROVAL', 'PAYMENT_REQUESTED', 'AWAITING_PAYMENT',
        'EXECUTION_REQUESTED', 'AWAITING_EXECUTION', 'IN_EXECUTION', 'COMPENSATING',
        'COMPENSATION_PENDING', 'COMPLETED', 'CANCELLED'
    )),
    CONSTRAINT ck_saga_attempts CHECK (step_attempts >= 0 AND payment_attempts >= 0)
);

-- No máximo uma Saga viva por OS: duas Sagas concorrentes cobrariam o cliente duas vezes.
CREATE UNIQUE INDEX ux_saga_active_work_order ON saga_instance (work_order_id)
    WHERE state NOT IN ('COMPLETED', 'CANCELLED');

-- Job de prazos: só as Sagas com prazo, da mais atrasada para a mais recente.
CREATE INDEX idx_saga_deadline ON saga_instance (deadline_at) WHERE deadline_at IS NOT NULL;

CREATE TABLE saga_step_log (
    id           BIGSERIAL PRIMARY KEY,
    saga_id      UUID         NOT NULL REFERENCES saga_instance (id),
    step         VARCHAR(40)  NOT NULL,
    message_id   UUID,
    outcome      VARCHAR(30)  NOT NULL,
    state_after  VARCHAR(30)  NOT NULL,
    detail       VARCHAR(500),
    occurred_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_saga_step_log_saga ON saga_step_log (saga_id, id);

-- ===================================================
-- 4. Outbox (publicação atômica com a transação) e inbox (consumidor idempotente)
-- ===================================================

CREATE TABLE outbox (
    id                UUID PRIMARY KEY,
    destination       VARCHAR(30)  NOT NULL,
    message_group_id  VARCHAR(64)  NOT NULL,
    message_type      VARCHAR(50)  NOT NULL,
    payload           JSONB        NOT NULL,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    published_at      TIMESTAMPTZ,
    attempts          INTEGER      NOT NULL DEFAULT 0,
    last_error        VARCHAR(500)
);

-- O relay só lê o que falta publicar, na ordem de criação.
CREATE INDEX idx_outbox_pending ON outbox (created_at) WHERE published_at IS NULL;

CREATE TABLE inbox (
    message_id    UUID        NOT NULL,
    consumer      VARCHAR(40) NOT NULL,
    processed_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (message_id, consumer)
);
