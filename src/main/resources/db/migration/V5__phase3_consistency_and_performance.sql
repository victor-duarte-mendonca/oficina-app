-- ===================================================
-- V5: Fase 3 — Consistência, performance e novo status do cliente.
--
-- Mudanças aditivas e de constraint apenas, garantindo compatibilidade com
-- versões anteriores da aplicação até que todas as réplicas sejam atualizadas.
-- ===================================================

-- ===================================================
-- 1. TIMESTAMP → TIMESTAMPTZ em todas as tabelas
-- Suporta múltiplas unidades em fusos horários diferentes.
-- ===================================================

ALTER TABLE clients
    ALTER COLUMN created_at TYPE TIMESTAMPTZ USING created_at AT TIME ZONE 'UTC',
    ALTER COLUMN updated_at TYPE TIMESTAMPTZ USING updated_at AT TIME ZONE 'UTC';

ALTER TABLE vehicles
    ALTER COLUMN created_at TYPE TIMESTAMPTZ USING created_at AT TIME ZONE 'UTC',
    ALTER COLUMN updated_at TYPE TIMESTAMPTZ USING updated_at AT TIME ZONE 'UTC';

ALTER TABLE parts
    ALTER COLUMN created_at TYPE TIMESTAMPTZ USING created_at AT TIME ZONE 'UTC',
    ALTER COLUMN updated_at TYPE TIMESTAMPTZ USING updated_at AT TIME ZONE 'UTC';

ALTER TABLE service_items
    ALTER COLUMN created_at TYPE TIMESTAMPTZ USING created_at AT TIME ZONE 'UTC',
    ALTER COLUMN updated_at TYPE TIMESTAMPTZ USING updated_at AT TIME ZONE 'UTC';

ALTER TABLE work_orders
    ALTER COLUMN created_at TYPE TIMESTAMPTZ USING created_at AT TIME ZONE 'UTC',
    ALTER COLUMN diagnosis_started_at TYPE TIMESTAMPTZ USING diagnosis_started_at AT TIME ZONE 'UTC',
    ALTER COLUMN sent_for_approval_at TYPE TIMESTAMPTZ USING sent_for_approval_at AT TIME ZONE 'UTC',
    ALTER COLUMN approved_at TYPE TIMESTAMPTZ USING approved_at AT TIME ZONE 'UTC',
    ALTER COLUMN execution_started_at TYPE TIMESTAMPTZ USING execution_started_at AT TIME ZONE 'UTC',
    ALTER COLUMN finished_at TYPE TIMESTAMPTZ USING finished_at AT TIME ZONE 'UTC',
    ALTER COLUMN delivered_at TYPE TIMESTAMPTZ USING delivered_at AT TIME ZONE 'UTC',
    ALTER COLUMN cancelled_at TYPE TIMESTAMPTZ USING cancelled_at AT TIME ZONE 'UTC';

ALTER TABLE app_users
    ALTER COLUMN created_at TYPE TIMESTAMPTZ USING created_at AT TIME ZONE 'UTC',
    ALTER COLUMN updated_at TYPE TIMESTAMPTZ USING updated_at AT TIME ZONE 'UTC';

-- ===================================================
-- 2. Novo campo: clients.status
-- Requisito R3: Lambda precisa consultar existência E status do cliente.
-- ===================================================

ALTER TABLE clients
    ADD COLUMN IF NOT EXISTS status VARCHAR(10) NOT NULL DEFAULT 'ACTIVE',
    ADD CONSTRAINT ck_clients_status CHECK (status IN ('ACTIVE', 'INACTIVE', 'BLOCKED'));

-- Índice parcial: consulta do Lambda é sempre "cpf_cnpj = ? AND status = 'ACTIVE'"
CREATE INDEX IF NOT EXISTS idx_clients_cpf_active
    ON clients (cpf_cnpj) WHERE status = 'ACTIVE';

-- ===================================================
-- 3. Normalização: clients.email sempre lowercase
-- ===================================================

ALTER TABLE clients
    ADD CONSTRAINT ck_clients_email_lower CHECK (email IS NULL OR email = lower(email));

-- ===================================================
-- 4. work_orders.order_number → NOT NULL
-- Garantir rastreabilidade: todo número deve ser único e presente.
-- ===================================================

-- Backfill: order_number nulo recebe um uuid_short
UPDATE work_orders SET order_number = SUBSTR(MD5(RANDOM()::TEXT), 1, 20)
    WHERE order_number IS NULL;

ALTER TABLE work_orders
    ALTER COLUMN order_number SET NOT NULL;

-- ===================================================
-- 5. Constraints de domínio (garantem integridade para qualquer cliente do banco)
-- ===================================================

ALTER TABLE work_orders
    ADD CONSTRAINT ck_work_orders_total_cost CHECK (total_cost >= 0),
    ADD CONSTRAINT ck_work_orders_status CHECK (status IN (
        'RECEIVED', 'IN_DIAGNOSIS', 'AWAITING_APPROVAL', 'APPROVED',
        'IN_PROGRESS', 'FINISHED', 'DELIVERED', 'CANCELLED'
    ));

ALTER TABLE work_order_parts
    ADD CONSTRAINT ck_work_order_parts_quantity CHECK (quantity > 0),
    ADD CONSTRAINT ck_work_order_parts_unit_price CHECK (unit_price >= 0);

ALTER TABLE work_order_services
    ADD CONSTRAINT ck_work_order_services_price CHECK (price >= 0);

ALTER TABLE parts
    ADD CONSTRAINT ck_parts_stock CHECK (stock_quantity >= 0),
    ADD CONSTRAINT ck_parts_minimum_stock CHECK (minimum_stock >= 0),
    ADD CONSTRAINT ck_parts_unit_price CHECK (unit_price >= 0);

ALTER TABLE service_items
    ADD CONSTRAINT ck_service_items_base_price CHECK (base_price >= 0),
    ADD CONSTRAINT ck_service_items_duration CHECK (estimated_duration_minutes > 0);

ALTER TABLE vehicles
    ADD CONSTRAINT ck_vehicles_production_year CHECK (production_year BETWEEN 1900 AND 2100);

-- ===================================================
-- 6. Remover índices duplicados (UNIQUE já cria índice automático)
-- ===================================================

DROP INDEX IF EXISTS idx_clients_cpf_cnpj;
DROP INDEX IF EXISTS idx_vehicles_plate;

-- ===================================================
-- 7. Criar índices de FK ausentes
-- Evita sequential scan em validação de FK.
-- ===================================================

CREATE INDEX IF NOT EXISTS idx_work_order_parts_part_id
    ON work_order_parts (part_id);

CREATE INDEX IF NOT EXISTS idx_work_order_services_service_item_id
    ON work_order_services (service_item_id);

-- ===================================================
-- 8. Índices para dashboard (R7)
-- "Volume diário" e "tempo médio por status" dependem de work_orders.
-- ===================================================

CREATE INDEX IF NOT EXISTS idx_work_orders_created_at
    ON work_orders (created_at DESC);

CREATE INDEX IF NOT EXISTS idx_work_orders_status_created_at
    ON work_orders (status, created_at DESC);

-- ===================================================
-- 9. Índice para GET /api/work-orders/me
-- Filtro por client_id com ordem por data.
-- ===================================================

DROP INDEX IF EXISTS idx_work_orders_client;
CREATE INDEX IF NOT EXISTS idx_work_orders_client_created_at
    ON work_orders (client_id, created_at DESC);
