# Spec 07 — OS Service: refatoração do `oficina-app`

> Atende **R1** (serviço de Ordem de Serviço: abertura, atualização de status, **consulta de status e histórico**) e hospeda o orquestrador de **R6**.
> Depende de [02](02-persistencia-sql-nosql.md) §2, [03](03-mensageria-e-contratos.md), [04](04-saga-orquestrada.md). Mantém a compatibilidade com o que a Fase 3 entregou onde possível.

## 1. O que muda e o que não muda

**Não muda:** estrutura hexagonal; cadastros (clientes, veículos, peças, serviços); autenticação (Lambda de CPF, authorizer, JWT,
`X-Gateway-Key`); consulta pública por número da OS; controle de estoque; observabilidade da Fase 3.
**Muda:** a OS deixa de avançar de `AWAITING_APPROVAL` em diante por chamadas diretas — passa a avançar por **eventos da Saga**.

## 2. Domínio (`WorkOrder`, `WorkOrderStatus`)

- `WorkOrderStatus` ganha `AWAITING_PAYMENT` e `AWAITING_EXECUTION` (prioridades de listagem entre `AWAITING_APPROVAL` e `IN_EXECUTION`).
- Transições novas no agregado (cada uma só aceita o estado de origem correto):

| Método | De → Para | Disparada por |
|---|---|---|
| `requestApproval()` (substitui `sendForApproval`) | `IN_DIAGNOSIS → AWAITING_APPROVAL` | `send-for-approval` (após `BudgetGenerated`) |
| `revertToDiagnosis()` | `AWAITING_APPROVAL → IN_DIAGNOSIS` | `BudgetGenerationFailed` |
| `awaitPayment()` | `AWAITING_APPROVAL → AWAITING_PAYMENT` | `PaymentRequested` |
| `queueForExecution()` | `AWAITING_PAYMENT → AWAITING_EXECUTION` | `ExecutionEnqueued` |
| `startExecution()` (substitui `approve`) | `AWAITING_EXECUTION → IN_EXECUTION` | `ExecutionStarted` |
| `complete()` | `IN_EXECUTION → FINISHED` | `ExecutionFinished` |
| `deliver()` | `FINISHED → DELIVERED` | ato manual (inalterado) |
| `cancel()` | qualquer não terminal → `CANCELLED` | recusa/expiração/falha **ou** cancelamento manual (via Saga) |

- O token de aprovação e `approveWithToken/rejectWithToken` **saem** do agregado quando o Billing assumir (V8; [02](02-persistencia-sql-nosql.md) §5).
- `WorkOrderStatusChangedEvent` (e-mail ao cliente) continua para `FINISHED`/`DELIVERED`/`CANCELLED`; o e-mail de **orçamento** passa para o Billing.
- Histórico: toda transição grava `work_order_status_history` (inclusive as da Fase 3: `source=USER`).

## 3. API pública do OS — tabela de compatibilidade

| Rota da Fase 3 | Fase 4 | Observação |
|---|---|---|
| `PATCH /admin/work-orders/{id}/start-diagnosis` | **mantida**; também publica `DiagnosisStarted` (INFO) | |
| `PATCH /admin/work-orders/{id}/send-for-approval` | **mantida**, mas **assíncrona**: devolve `202`/OS em `IN_DIAGNOSIS` com `saga` em `BUDGET_REQUESTED`; vira `AWAITING_APPROVAL` ao chegar `BudgetGenerated` | muda o contrato (era síncrono): Postman e README atualizados |
| `PATCH /{id}/approve`, `/{id}/reject` (ADMIN) | **removidas**; equivalentes em `POST /admin/budgets/{orderNumber}/approve\|reject` (Billing) | responder `410 Gone` por 1 versão com ponteiro |
| `POST /me/work-orders/{id}/approval` | **removida**; equivalente `POST /me/budgets/{orderNumber}/approval` (Billing) | idem |
| `POST/GET /public/work-orders/{orderNumber}/approve\|reject` (token) | **removidas**; equivalente `/public/budgets/{orderNumber}/approve\|reject` (Billing) | |
| `PATCH /{id}/complete` | **removida como entrada do mecânico**; equivalente `POST /admin/executions/{id}/finish` (Execution) | `complete()` passa a ser interno |
| `PATCH /{id}/deliver` | **mantida** | |
| `PATCH /{id}/cancel` (ADMIN) | **mantida**; passa a iniciar a compensação quando há Saga ativa ([04](04-saga-orquestrada.md) passo C) | |
| `GET /public/work-orders/{orderNumber}/status` | **mantida**; resposta ganha `sagaState` público simplificado (`AWAITING_APPROVAL`, `AWAITING_PAYMENT`, ...) | sem dados pessoais |
| demais (`/admin/clients`, `/vehicles`, `/parts`, `/services`, `/me/*` exceto aprovação, `/auth/*`) | **inalteradas** | |

Rotas **novas**: `GET /admin/work-orders/{id}/history`, `GET /me/work-orders/{id}/history` (404 se de outro cliente),
`GET /admin/work-orders/{id}/saga` e `GET /admin/sagas/{id}`, `POST /admin/sagas/{id}/retry-compensation` (ADMIN).

## 4. Componentes novos (camada `application`/`infrastructure`)

| Componente | Camada | Responsabilidade |
|---|---|---|
| `WorkOrderSagaOrchestrator` | application (`service`) | implementa o §4 de [04](04-saga-orquestrada.md); só depende de ports |
| `SagaRepositoryPort`, `OutboxPort`, `InboxPort`, `MessageConsumerPort` | application (`ports/out`/`in`) | |
| `SagaRepositoryAdapter`, `OutboxRelay`, `SqsPoller`, `SagaTimeoutJob` | infrastructure | JPA, `@Scheduled`, SQS |
| `StatusHistoryRecorder` | application | grava histórico em toda transição |
| `BudgetItemsSnapshotMapper` | application (`mapper`) | monta o `GenerateBudget` a partir das linhas da OS |

Transações: cada resposta processada em **uma** transação (`inbox` + transição da OS + `saga_instance` + log + `outbox`).

## 5. Migração do fluxo legado

OS sem `saga_instance` terminam pelo caminho antigo (flag no agregado `legacy_flow=true`, preenchida pela V7 em OS não
terminais); novas OS usam a Saga. Após a Fase 4 validada, o caminho legado é removido (V8) — **não** na mesma entrega.

## 6. Critérios de aceite

- [ ] Suíte de 429+ testes da Fase 3 verde, adaptada onde o contrato mudou (lista das rotas do §3 conferida contra a matriz RBAC de 80 casos).
- [ ] Testes de unidade do orquestrador por linha do §4 de [04](04-saga-orquestrada.md) e do agregado por transição.
- [ ] `GET /history` devolve todas as transições (inclusive as da Saga) em ordem, com `correlationId`.
- [ ] `kubectl kustomize` dos overlays com as novas variáveis (`SQS_*`, `SAGA_*`), sem Secret versionado.
- [ ] Postman atualizado: pastas de aprovação/execução apontam para Billing/Execution; coleção "Run" da Fase 3 continua executável.
- [ ] Rollback: a versão anterior da imagem continua funcionando com o schema V7 (V7 aditiva).

## 7. Fora de escopo

Mover cadastros ou estoque; reescrever o domínio de OS; remover o fluxo legado (V8); front-end.
