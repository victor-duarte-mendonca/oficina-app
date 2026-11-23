# Spec 06 — Execution Service (execução e produção)

> Atende **R1** (serviço de Execução e Produção) e o uso do **banco NoSQL** de **R2**. Repositório novo: `oficina-execution-service`.
> Banco: [02](02-persistencia-sql-nosql.md) §4. Mensagens: [03](03-mensageria-e-contratos.md). Saga: [04](04-saga-orquestrada.md).

## 1. Responsabilidades (PDF)

1. **Gerenciar a fila de execução da OS** (ordenada por prioridade e antiguidade).
2. **Atualizar status durante diagnóstico e reparos** (andamento, notas técnicas, responsável).
3. **Comunicar a finalização ao OS Service** (`ExecutionFinished`) — e falhas (`ExecutionFailed`).

## 2. Modelo

Agregado **`ExecutionTask`** (um por OS e fase):

```
phase DIAGNOSIS: QUEUED → IN_PROGRESS → FINISHED        (informativa; não é passo da Saga)
phase REPAIR:    QUEUED → IN_PROGRESS → FINISHED | FAILED   (passo 5–7 da Saga)
                 QUEUED|IN_PROGRESS → CANCELLED          (CancelExecution)
```

- A fase **DIAGNOSIS** é aberta quando o OS publica `DiagnosisStarted` (evento informativo, `kind=INFO`, fora da Saga): o
  mecânico registra notas de diagnóstico; o OS mantém o seu próprio `IN_DIAGNOSIS`. Isso cobre o "diagnóstico" do PDF sem
  colocar a etapa no caminho crítico da Saga.
- A fase **REPAIR** nasce do comando `EnqueueExecution` (depois do pagamento). Responde `ExecutionEnqueued{position}`.
- Invariantes: no máximo uma tarefa **ativa** por (OS, fase); `start` só de `QUEUED`; `finish`/`fail` só de `IN_PROGRESS`;
  transições idempotentes; `version` com condição no DynamoDB.
- Prioridade: valor numérico do comando (padrão por antiguidade); mesma regra de ordenação da fila do OS
  (`IN_EXECUTION > AWAITING_APPROVAL > ...`) **não** se aplica aqui — a fila de Execution é só de trabalho a fazer.

## 3. API REST (via gateway; papéis ADMIN e MECHANIC)

| Método | Rota | Função |
|---|---|---|
| `GET` | `/admin/executions?phase=&status=&page=` | fila ordenada (GSI1) |
| `GET` | `/admin/executions/{workOrderId}` | tarefa + notas |
| `POST` | `/admin/executions/{workOrderId}/start` | inicia (define `assignee` = `sub` do JWT) → `ExecutionStarted` |
| `POST` | `/admin/executions/{workOrderId}/notes` | nota técnica (`{text, kind}`); em REPAIR gera `ExecutionProgress` (INFO) |
| `POST` | `/admin/executions/{workOrderId}/finish` | conclui → `ExecutionFinished` |
| `POST` | `/admin/executions/{workOrderId}/fail` | `{reason}` → `ExecutionFailed` (dispara compensação na Saga) |
| `GET` | `/me/work-orders/{id}/progress` | **não** neste serviço: o cliente vê o andamento pelo OS ([07](07-os-service-refatoracao.md) §4) |

`{workOrderId}` é o id numérico da OS (chave natural herdada do evento). Os endpoints de `PATCH /admin/work-orders/{id}/complete`
do OS **deixam de existir** como entrada do mecânico (ver [07](07-os-service-refatoracao.md) §3).

## 4. Consumo e publicação

- Consome `execution-commands.fifo`: `EnqueueExecution`, `CancelExecution`, `DiagnosisStarted` (informativo).
- Publica em `os-saga-replies.fifo` via outbox DynamoDB ([02](02-persistencia-sql-nosql.md) §4).
- `CancelExecution` em tarefa `IN_PROGRESS`: marca `CANCELLED`, preserva as notas (auditoria) e responde `ExecutionCancelled`;
  em tarefa já `FINISHED` responde `ExecutionCancelled` com `alreadyFinished=true` e o OS decide (a Saga trata como erro de negócio → intervenção).

## 4.1 Implementação (2026-11-23, repositório local `../oficina-execution-service`)

- Quarkus 3.15.7 / Java 21, hexagonal (`domain` sem framework). Agregado `ExecutionTask` com transições idempotentes e eventos
  registrados (`pullEvents`); `ExecutionCommandHandler` despacha por **Strategy** (`EnqueueExecution`, `CancelExecution`,
  `DiagnosisStarted`); `ExecutionWorkService` atende o mecânico.
- **Unit of Work** no DynamoDB (`DynamoUnitOfWork`): tarefa (trava otimista pela `version`) + notas + outbox + inbox numa única
  `TransactWriteItems`; o motivo de cancelamento do item do inbox distingue "mensagem repetida" de "concorrência". Tabela única
  com GSI1 (fila) e GSI2 esparso (outbox pendente); TTL no inbox e no outbox publicado.
- Diferenças em relação ao rascunho: SK da tarefa é `TASK#<fase>` (uma tarefa por OS **e fase**); `ExecutionEnqueueFailed` é
  respondido quando a regra recusa (ex.: reparo já concluído) — a Saga estorna; `CancelExecution` em OS sem reparo confirma.
- Rotas como no §3, com `?phase=` opcional (padrão `REPAIR`) em `start`/`notes`/`finish`; o responsável é o `sub` do JWT.
- Entregáveis no repositório: `Dockerfile` (imagem verificada localmente), `k8s/` (1→2 réplicas, só a chave **pública** do JWT,
  NetworkPolicy com IMDS do ADR 0022), `.github/workflows/ci.yml`, `docker-compose.yml` com LocalStack, `contracts/` (dono de
  `execution-events.schema.json`; inclui `examples/os-commands.json`), README com a justificativa do NoSQL.
- **Testes:** 66 (domínio, casos de uso em memória, contrato dos dois lados, LocalStack DynamoDB+SQS, API com JWT real);
  cobertura 97,3% linhas / 84,9% ramos; gate BUNDLE 80/70 e pacotes de domínio/aplicação 80%.
- **Pendente:** criar o repositório no GitHub (usuário) e o push; deploy no k3s e o `execution_queue_depth` (gauge) com a
  infraestrutura da spec 12; BDD de serviço (spec 09).

## 5. Critérios de aceite

- [ ] Testes de domínio de todas as transições (válidas e inválidas) do `ExecutionTask`.
- [ ] Teste com DynamoDB local: ordenação da fila, concorrência (2 mecânicos iniciando a mesma tarefa → 1 vence), idempotência de `EnqueueExecution`.
- [ ] Contrato: `EnqueueExecution` → `ExecutionEnqueued`; `finish` → `ExecutionFinished`; `fail` → `ExecutionFailed`.
- [ ] RBAC: `CLIENT` recebe 403 em `/admin/executions/**`; sem JWT 401 (via gateway) e sem `X-Gateway-Key` 403 no EIP.
- [ ] Métricas: `execution_queue_depth`, `execution_task_duration_seconds` (por fase), `execution_failures_total`.

## 6. Fora de escopo

Atribuição automática de mecânico/box; estimativa de prazo; agenda; controle de horas; qualquer cálculo financeiro (é do Billing).
