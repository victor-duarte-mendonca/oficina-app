# Spec 03 — Mensageria assíncrona e contratos

> Atende **R4** (REST síncrono quando necessário + mensageria assíncrona) e a metade "mensageria" de **R14**.
> ADRs: [0020](../../docs/adr/0020-mensageria-sqs-fifo.md) (SQS FIFO) e [0019](../../docs/adr/0019-saga-orquestrada-no-os-service.md).
> Decisão **D2** (aberta): SQS (recomendado) × RabbitMQ. Esta spec assume SQS; os contratos (§3–§4) valem para qualquer broker.

## 1. Topologia (SQS FIFO)

Convenção de nomes: `oficina-{env}-{dono}-{tipo}.fifo`; cada fila tem DLQ `...-dlq.fifo` (**DLQ de fila FIFO também precisa ser FIFO** — a confirmar na doc da AWS).

| Fila | Produtor | Consumidor | Conteúdo |
|---|---|---|---|
| `billing-commands` | OS | Billing | `GenerateBudget`, `RequestPayment`, `VoidBudget`, `RefundPayment` |
| `execution-commands` | OS | Execution | `EnqueueExecution`, `CancelExecution` |
| `os-saga-replies` | Billing, Execution | OS | todos os eventos de resposta (§3.2) |
| `os-notifications` (opcional) | Billing, Execution | OS | eventos informativos fora da Saga (`ExecutionProgress`) — vão em `os-saga-replies` com `kind=INFO` (a fila `os-notifications` só se o volume justificar) |

`MessageGroupId = workOrderId` em todas: ordem estrita **por OS**, paralelismo entre OS. `MessageDeduplicationId = messageId`
(janela de 5 min do SQS) — **não substitui** a idempotência do consumidor (§5). Visibilidade 60 s; `maxReceiveCount = 5` → DLQ;
retenção 4 dias; DLQ 14 dias.

## 2. Envelope (cabeçalho comum, atributos da mensagem + corpo JSON)

```json
{
  "messageId": "uuid-v7",
  "type": "BudgetApproved",
  "version": 1,
  "kind": "COMMAND | EVENT",
  "sagaId": "uuid",
  "workOrderId": 123,
  "orderNumber": "OS-000123",
  "correlationId": "X-Correlation-Id da requisição de origem",
  "causationId": "messageId que provocou esta mensagem",
  "occurredAt": "2026-11-22T14:00:00Z",
  "traceparent": "00-<traceId>-<spanId>-01",
  "payload": { }
}
```

Regras: `traceparent` e `correlationId` **sempre** propagados (ver [13](13-observabilidade-distribuida.md)); `payload` sem CPF, sem
token de aprovação em claro e sem credenciais; evolução **aditiva** (campo novo opcional; remoção/renome = `version` nova e
período de convivência). Tamanho máximo 256 KB (limite do SQS) — nenhum payload deve passar de ~10 KB.

## 3. Catálogo

### 3.1 Comandos (OS → participantes)

| Tipo | Destino | Payload | Resposta esperada |
|---|---|---|---|
| `GenerateBudget` | billing | `clientId`, `customer{name,email}`, `items[{kind,refId,name,qty,unitPrice}]`, `total`, `approvalTtlSeconds` | `BudgetGenerated` \| `BudgetGenerationFailed` |
| `RequestPayment` | billing | `budgetId`, `amount` | `PaymentRequested` \| `PaymentRequestFailed` |
| `VoidBudget` | billing | `budgetId`, `reason` | `BudgetVoided` |
| `RefundPayment` | billing | `paymentId`, `reason` | `PaymentRefunded` \| `PaymentRefundFailed` |
| `EnqueueExecution` | execution | `vehicle{brand,model,plate}`, `items[]`, `priority` | `ExecutionEnqueued` \| `ExecutionEnqueueFailed` |
| `CancelExecution` | execution | `reason` | `ExecutionCancelled` |
| `DiagnosisStarted` | execution | `kind=INFO`; `vehicle{brand,model,plate}`, `notes` | nenhuma (abre a fase DIAGNOSIS; fora da Saga) |

### 3.2 Eventos (participantes → OS)

| Origem | Tipos |
|---|---|
| Billing | `BudgetGenerated{budgetId,total,expiresAt}`, `BudgetGenerationFailed{reason}`, `BudgetApproved{budgetId,by}`, `BudgetRejected{budgetId,by}`, `BudgetExpired`, `BudgetVoided`, `PaymentRequested{paymentId,expiresAt}`, `PaymentRequestFailed{reason}`, `PaymentApproved{paymentId,amount}`, `PaymentRejected{paymentId,detail}`, `PaymentExpired{paymentId}`, `PaymentRefunded{paymentId}`, `PaymentRefundFailed{paymentId,detail}` |
| Execution | `ExecutionEnqueued{position}`, `ExecutionEnqueueFailed{reason}`, `ExecutionStarted{assignee}`, `ExecutionFinished{summary}`, `ExecutionFailed{reason}`, `ExecutionCancelled`, `ExecutionProgress{phase,note}` (informativo, `kind=INFO`, não avança a Saga) |

`by` ∈ {`CLIENT_TOKEN`, `CLIENT_JWT`, `STAFF`}. Todo evento carrega o `sagaId` recebido no comando; eventos **originados fora da
Saga** (cliente aprova pelo link) usam o `sagaId` guardado no orçamento.

## 4. Contratos formais

- Esquemas JSON Schema 2020-12 em `contracts/`, **um arquivo por produtor** (`envelope.schema.json`, `os-commands.schema.json`,
  `billing-events.schema.json`, `execution-events.schema.json`), versionados com as mensagens; exemplos válidos e inválidos em
  `contracts/examples/`. Enquanto os repositórios do Billing e do Execution não existem, o catálogo inteiro mora no `oficina-app`
  (implementado em 2026-11-23; ver `contracts/README.md`).
- **Teste de contrato** em cada lado: o produtor valida suas mensagens contra o schema; o consumidor valida os exemplos do schema
  do produtor (cópia sincronizada por script `scripts/sync-contracts.sh`, falha no CI se divergir). Não há repositório de contratos
  separado (exigiria um 6º repositório).
- REST: OpenAPI exportado por serviço (`docs/api/openapi.{yaml,json}`), como já feito no OS.

## 5. Garantias e padrões

| Problema | Padrão | Onde |
|---|---|---|
| Gravar estado **e** publicar atomicamente | **Outbox** (tabela/itens) + relay | OS/Billing: `@Scheduled` a cada 1 s com `FOR UPDATE SKIP LOCKED`; Execution: GSI2 |
| Entrega *at-least-once* | **Inbox** / consumidor idempotente por `messageId` | os três serviços; mensagem repetida = `ack` sem efeito |
| Mensagem fora de ordem entre OS | `MessageGroupId` por OS | SQS FIFO |
| Falha de processamento | retry por visibilidade (até 5) → **DLQ** | alerta em profundidade da DLQ > 0 |
| Mensagem venenosa | DLQ + `saga_step_log` com `outcome=POISON` | runbook de reprocesso ([12](12-infra-k8s-mensageria.md) §7) |
| Consumidor lento / pico | `maxNumberOfMessages=10`, long polling 20 s, HPA por CPU (já existente) | |

**Consumo** (verificação Q1: não há conector SmallRye Reactive Messaging para SQS na doc lida): *poller* próprio — componente de infraestrutura
`SqsPoller` (virtual threads, long polling, `DeleteMessage` só após o `commit` do tratamento); isolado atrás de
`MessageConsumerPort` para o domínio não conhecer SQS.

> **Implementado (2026-11-23).** Q2 resolvida evitando a dependência: o OS usa o **AWS SDK v2 oficial** (`software.amazon.awssdk:sqs`
> 2.59.4, cliente HTTP do JDK `url-connection-client`, sem Netty/Apache) em vez da extensão Quarkiverse, cuja versão compatível
> com Quarkus 3.15 não foi confirmada. Peças: `MessageTransport` (porta interna da infraestrutura) → `SqsMessageTransport`;
> `OutboxPublisher` (lote com `FOR UPDATE SKIP LOCKED`, para na primeira falha para não furar a ordem por OS); `SagaReplyConsumer`
> (confirma só após o `commit`); `SagaJobs` (`quarkus-scheduler`: relay 1 s, respostas 1 s, prazos 10 s; pulados com a
> mensageria/Saga desligada). A porta de entrada é `HandleSagaMessageUseCase` (não há `MessageConsumerPort` separada). Endpoint
> de teste por `app.messaging.endpoint-override` (LocalStack 3.8.1 no `SqsMessageTransportTest`). Configuração: `app.messaging.*`
> e `SQS_*_URL`. **Pendente:** extrair o `traceparent` no consumo para continuar o trace (spec 13 §1; hoje ele já é gravado na
> publicação).

## 6. Síncrono × assíncrono (texto para o README)

REST fica no **perímetro** (cliente/funcionário → serviço) e no webhook; o que precisa de consistência entre serviços é
assíncrono. Uma chamada REST OS → Billing dentro da Saga faria o OS falhar quando o Billing estiver fora do ar e impediria a
compensação limpa; a fila absorve indisponibilidade e dá retry/DLQ de graça.

## 7. Critérios de aceite

- [ ] Schemas de todas as mensagens do §3 + teste de contrato verde nos dois lados.
- [ ] Teste: mensagem duplicada (mesmo `messageId`) não duplica efeito em nenhum consumidor.
- [ ] Teste: falha após o `commit` e antes do `DeleteMessage` reentrega sem efeito duplicado.
- [ ] Teste: 5 falhas levam à DLQ e disparam o alerta.
- [ ] `traceparent`/`correlationId` visíveis nos logs dos três serviços para uma mesma OS.

## 8. Fora de escopo

Schema registry; Kafka; SNS (entra só se surgir um 2º consumidor dos eventos); *exactly-once* "de verdade".
