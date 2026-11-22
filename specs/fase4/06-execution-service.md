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

## 5. Critérios de aceite

- [ ] Testes de domínio de todas as transições (válidas e inválidas) do `ExecutionTask`.
- [ ] Teste com DynamoDB local: ordenação da fila, concorrência (2 mecânicos iniciando a mesma tarefa → 1 vence), idempotência de `EnqueueExecution`.
- [ ] Contrato: `EnqueueExecution` → `ExecutionEnqueued`; `finish` → `ExecutionFinished`; `fail` → `ExecutionFailed`.
- [ ] RBAC: `CLIENT` recebe 403 em `/admin/executions/**`; sem JWT 401 (via gateway) e sem `X-Gateway-Key` 403 no EIP.
- [ ] Métricas: `execution_queue_depth`, `execution_task_duration_seconds` (por fase), `execution_failures_total`.

## 6. Fora de escopo

Atribuição automática de mecânico/box; estimativa de prazo; agenda; controle de horas; qualquer cálculo financeiro (é do Billing).
