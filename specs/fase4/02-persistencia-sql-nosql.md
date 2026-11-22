# Spec 02 — Persistência: SQL e NoSQL por serviço

> Atende **R2** (≥ 1 banco relacional e ≥ 1 não relacional) e a metade "banco próprio" de **R1**/**R5**.
> ADR de apoio: [0018](../../docs/adr/0018-divisao-em-tres-microsservicos-e-bancos.md). Depende de [01](01-microsservicos-e-fronteiras.md).

## 1. Escolha por serviço (justificativa para o PDF)

| Serviço | Banco | Por quê | Quando outro seria melhor |
|---|---|---|---|
| OS | **PostgreSQL** (RDS existente) | relacionamentos (cliente → veículo → OS → linhas), estoque transacional, relatórios por status | — |
| Billing | **PostgreSQL** (RDS próprio) | dinheiro exige ACID, unicidade (um pagamento ativo por orçamento) e auditoria | DynamoDB com escrita condicional também serviria; PostgreSQL é mais simples de auditar |
| Execution | **DynamoDB** | acesso por chave (`workOrderId`) e fila ordenada; esquema flexível para notas/etapas; sem custo ocioso nem memória no nó | PostgreSQL se houver relatórios ad hoc sobre a execução |

Decisão **D3** (aberta): RDS próprio para o Billing (recomendado) × *database* separado na instância atual (mais barato, mas
enfraquece "banco e infraestrutura próprios"). O restante desta spec vale nos dois casos; só muda o Terraform ([12](12-infra-k8s-mensageria.md)).

## 2. OS Service — migration V7 (Flyway; migrations pertencem à aplicação, [ADR 0014](../../docs/adr/0014-migrations-pertencem-a-aplicacao.md))

| Mudança | Detalhe |
|---|---|
| `work_orders.status` | CHECK ampliado com `AWAITING_PAYMENT` e `AWAITING_EXECUTION` (ver [07](07-os-service-refatoracao.md) §2) |
| `work_order_status_history` | `id`, `work_order_id` FK, `from_status`, `to_status`, `occurred_at`, `source` (`USER`/`SAGA`/`SYSTEM`), `actor`, `reason`, `correlation_id`. Índice `(work_order_id, occurred_at)`. Atende "consulta de status e histórico" |
| `saga_instance` | `id` (UUID = `sagaId`), `work_order_id` UNIQUE parcial onde `state` não-terminal, `state`, `step`, `attempt`, `deadline_at`, `context` JSONB (ids de orçamento/pagamento), `version` (otimista), `created_at`, `updated_at` |
| `saga_step_log` | `id`, `saga_id` FK, `step`, `direction` (`FORWARD`/`COMPENSATE`), `message_id`, `outcome`, `detail`, `occurred_at` — trilha de auditoria usada no vídeo |
| `outbox` | `id`, `message_group_id`, `message_type`, `payload` JSONB, `headers` JSONB, `created_at`, `published_at` NULL; índice parcial `WHERE published_at IS NULL` |
| `inbox` | `message_id` PK, `consumer`, `processed_at` — deduplicação de consumidor |
| Remoções | `approval_token*` saem de `work_orders` **depois** que o Billing assumir (migration V8, em outra etapa, nunca na mesma V7) |

Regras: V7 é **aditiva e retrocompatível** (a versão anterior da app continua funcionando contra o schema novo — requisito do
rollback automático). Testada com Testcontainers PostgreSQL 16, como as V5/V6.

## 3. Billing Service — PostgreSQL (Flyway próprio)

| Tabela | Colunas principais | Invariantes |
|---|---|---|
| `budget` | `id` UUID, `work_order_id`, `order_number`, `client_id`, `customer_name`, `customer_email`, `total` NUMERIC(12,2), `status` (`DRAFT,SENT,APPROVED,REJECTED,EXPIRED,VOIDED`), `approval_token_hash`, `token_consumed_at`, `expires_at`, `version` | UNIQUE `(work_order_id)` onde status não-terminal; token guardado como **hash** (não o valor) |
| `budget_line` | `budget_id`, `kind` (`PART`/`SERVICE`), `ref_id`, `name`, `quantity`, `unit_price` | `total = Σ quantity × unit_price` verificado no domínio |
| `payment` | `id` UUID, `budget_id`, `mp_payment_id` UNIQUE, `amount`, `status` (`PENDING,APPROVED,REJECTED,EXPIRED,REFUND_PENDING,REFUNDED,REFUND_FAILED`), `idempotency_key` UNIQUE, `qr_code`, `expires_at`, `attempt` | UNIQUE parcial `(budget_id)` onde status ∈ {PENDING, APPROVED}: **um** pagamento ativo por orçamento |
| `payment_event` | `id`, `payment_id`, `source` (`WEBHOOK`/`POLL`/`API`), `mp_status`, `raw` JSONB (sem dados de pagador), `received_at` | trilha para reconciliação |
| `outbox`, `inbox` | iguais ao OS | |

LGPD: `customer_email` e nome são o mínimo para enviar o orçamento; CPF/CNPJ **não** é copiado para o Billing (o Pix de
sandbox não o exige; em produção seria opt-in). Nada de PAN/cartão: só Pix.

## 4. Execution Service — DynamoDB (tabela única `oficina-{env}-execution`)

Modo **on-demand** (sem capacidade provisionada → sem custo ocioso), PITR desligado no Academy, TTL ativo no atributo `ttl`.

| Item | PK | SK | Atributos |
|---|---|---|---|
| Tarefa da OS | `WO#<workOrderId>` | `TASK` | `orderNumber`, `phase` (`DIAGNOSIS`/`REPAIR`), `status` (`QUEUED,IN_PROGRESS,FINISHED,FAILED,CANCELLED`), `priority`, `queuedAt`, `startedAt`, `finishedAt`, `assignee`, `vehicle{}`, `items[]`, `version` |
| Nota/etapa | `WO#<workOrderId>` | `NOTE#<ulid>` | `author`, `text`, `at`, `kind` |
| Deduplicação | `MSG#<messageId>` | `INBOX` | `processedAt`, `ttl` (7 dias) |
| Outbox | `OUT#<messageId>` | `PENDING`/`SENT` | `type`, `payload`, `group`, `ttl` |

Índices: **GSI1** `queue-index` (PK `phase#status`, SK `priority#queuedAt`) para a fila ordenada; **GSI2** `outbox-index`
(PK `outboxStatus`, SK `createdAt`) para o relay. Padrões de acesso: (1) tarefa por OS (PK), (2) próximos da fila (GSI1),
(3) notas da OS (PK + `begins_with NOTE#`), (4) outbox pendente (GSI2).

Atomicidade: `TransactWriteItems` grava **estado + `MSG#` (condicional `attribute_not_exists`) + `OUT#`** numa operação — equivale
a inbox + outbox do SQL. Concorrência: `version` com `ConditionExpression`.

## 5. Evolução de schema e migração de dados

- OS: Flyway (V7 agora; V8 limpa o token de aprovação quando o Billing for o dono).
- Billing: Flyway próprio (`V1__baseline.sql`), executado pela app no startup (mesma política do ADR 0014).
- Execution: sem migrations; mudanças de atributo são aditivas e versionadas por `schemaVersion` no item.
- Dados existentes (OS em andamento): nenhuma migração para Billing/Execution — OS criadas **antes** da V7 terminam o ciclo
  pelo caminho legado (`saga_instance` ausente = fluxo antigo); o seed de demonstração cria OS já no modelo novo.

## 6. Critérios de aceite

- [ ] V7 executa contra PostgreSQL 16 real (Testcontainers) e a suíte da Fase 3 continua verde.
- [ ] Billing: índices parciais impedem 2 pagamentos ativos por orçamento (teste de concorrência com 2 transações).
- [ ] Execution: teste com DynamoDB Local/LocalStack prova idempotência (`MSG#` duplicado) e fila ordenada pelo GSI1.
- [ ] Nenhum serviço tem credencial/endpoint do banco de outro (conferência dos Secrets e do Terraform).
- [ ] Justificativa SQL × NoSQL transcrita no README de cada serviço e no PDF.

## 7. Fora de escopo

Réplicas de leitura; particionamento; backup/restore automatizado além do que o RDS do Academy já oferece; DAX.
