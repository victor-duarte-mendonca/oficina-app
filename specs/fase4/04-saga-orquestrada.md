# Spec 04 — Saga orquestrada (fluxo transacional da OS)

> Atende **R6** (Saga com rollback/compensação em qualquer etapa) e **R7** (escolha documentada e justificada no README).
> ADR: [0019](../../docs/adr/0019-saga-orquestrada-no-os-service.md). Mensagens em [03](03-mensageria-e-contratos.md). Dono: **OS Service**.

## 1. Escolha e justificativa (texto-base para o README e o PDF)

**Orquestração** com o orquestrador dentro do OS Service. O OS já é dono do agregado `WorkOrder`; cada passo da Saga é uma
transição desse agregado, então o estado da Saga e o estado da OS ficam no **mesmo banco e na mesma transação**. Coreografia
espalharia as 5+ etapas e suas compensações por três serviços (cada um reagindo a eventos do outro), tornando o fluxo difícil de
depurar, de testar e de demonstrar. Custo aceito: o OS conhece os comandos dos participantes (acoplamento de contrato) e é um
ponto central — mitigado por estado persistido, retomada por timeout e DLQ. Outra abordagem seria melhor com muitos
participantes independentes e fluxos curtos.

## 2. Máquina de estados da OS (muda a da Fase 3)

```
RECEIVED → IN_DIAGNOSIS → AWAITING_APPROVAL → AWAITING_PAYMENT → AWAITING_EXECUTION → IN_EXECUTION → FINISHED → DELIVERED
     \___________\______________\__________________\____________________\_________________/
                                  → CANCELLED (a partir de qualquer estado não terminal, via compensação)
```

`AWAITING_PAYMENT` e `AWAITING_EXECUTION` são novos (V7). Estados terminais e prioridades de listagem: ver [07](07-os-service-refatoracao.md) §2.

## 3. Estados da Saga (`saga_instance.state`)

`BUDGET_REQUESTED` → `AWAITING_APPROVAL` → `PAYMENT_REQUESTED` → `AWAITING_PAYMENT` → `EXECUTION_REQUESTED` →
`AWAITING_EXECUTION` → `IN_EXECUTION` → **`COMPLETED`**.
Compensação: `COMPENSATING` (com `step` indicando o que desfazer) → **`CANCELLED`** ou **`COMPENSATION_PENDING`** (estorno
falhou; exige intervenção).

A Saga **começa** em `PATCH /admin/work-orders/{id}/send-for-approval` (OS em `IN_DIAGNOSIS`) e **termina** em `COMPLETED`
(quando a Execução termina; `deliver` é ato manual posterior e fora da Saga) ou em `CANCELLED`/`COMPENSATION_PENDING`.

## 4. Passos, caminho feliz e compensações

| # | Gatilho | Comando | Sucesso (evento → efeito local) | Falha / timeout → compensação |
|---|---|---|---|---|
| 1 | `send-for-approval` | `GenerateBudget` | `BudgetGenerated` → OS `AWAITING_APPROVAL` | `BudgetGenerationFailed` ou sem resposta em 30 s (3 tentativas) → OS volta a `IN_DIAGNOSIS`; Saga `CANCELLED` (nada a desfazer fora do OS) |
| 2 | decisão do cliente/funcionário | — (evento do Billing) | `BudgetApproved` → passo 3 | `BudgetRejected` / `BudgetExpired` (TTL de aprovação) → `VoidBudget`; restaura estoque; OS `CANCELLED` |
| 3 | `BudgetApproved` | `RequestPayment` | `PaymentRequested` → OS `AWAITING_PAYMENT` | `PaymentRequestFailed` (MP indisponível, 3 tentativas com *backoff*) → `VoidBudget`; OS `CANCELLED` |
| 4 | pagamento no Mercado Pago | — | `PaymentApproved` → passo 5 | `PaymentRejected` ou `PaymentExpired`: até **2 novas tentativas** (novo `RequestPayment`, novo Pix); esgotadas → `VoidBudget`; OS `CANCELLED` |
| 5 | `PaymentApproved` | `EnqueueExecution` | `ExecutionEnqueued` → OS `AWAITING_EXECUTION` | `ExecutionEnqueueFailed` ou sem resposta → **`RefundPayment`** → `PaymentRefunded` → `VoidBudget` → OS `CANCELLED` |
| 6 | mecânico inicia | — | `ExecutionStarted` → OS `IN_EXECUTION` | — |
| 7 | mecânico encerra | — | `ExecutionFinished` → OS `FINISHED`; Saga `COMPLETED`; notifica cliente | `ExecutionFailed` (peça indisponível, defeito sem reparo) → `RefundPayment` → `VoidBudget` → OS `CANCELLED`; estoque restaurado |
| C | **cancelamento manual** (`PATCH /{id}/cancel`, ADMIN) com Saga ativa | conforme o estado: `CancelExecution` e/ou `RefundPayment` e/ou `VoidBudget` | — | mesma cadeia de compensação, em ordem **inversa** à dos passos já concluídos |

**Compensação do estorno falhar** (`PaymentRefundFailed` após 3 tentativas): Saga em `COMPENSATION_PENDING`, OS **permanece**
no último estado seguro, alerta crítico (ver [13](13-observabilidade-distribuida.md)), `POST /admin/sagas/{id}/retry-compensation`
(ADMIN) reexecuta. Nunca se marca `CANCELLED` com dinheiro retido.

## 5. Regras de orquestração

1. **Um passo por vez**: o orquestrador só emite o comando seguinte ao processar a resposta do atual (`version` otimista em
   `saga_instance` impede duas threads avançarem a mesma Saga).
2. **Idempotência**: cada resposta é processada uma vez (`inbox`); transição já aplicada → ignora (evento atrasado/duplicado).
3. **Resposta inesperada para o estado atual** (ex.: `PaymentApproved` depois da Saga cancelada) → *compensa o efeito*: se há
   pagamento aprovado sem OS viva, envia `RefundPayment` e registra `outcome=ORPHAN_PAYMENT` no log. Cobre a corrida
   "pagou depois que expirou".
4. **Timeouts** (`deadline_at`) verificados por *job* a cada 10 s: aprovação (padrão 72 h; **configurável para 2 min na demo**),
   pagamento (Pix 30 min; configurável), enfileiramento (60 s), compensação (60 s/tentativa). Estourou → tratado como falha do passo.
5. **Compensações são idempotentes e reexecutáveis** (`VoidBudget` em orçamento já anulado = sucesso; `RefundPayment` em
   pagamento já estornado = sucesso).
6. **Cada transição** grava `saga_step_log` + `work_order_status_history` + `outbox` na **mesma transação** (ver [02](02-persistencia-sql-nosql.md)).
7. Estoque (`restoreStockOfAllParts`) é compensação **local** do OS, disparada em todo caminho que leva a `CANCELLED`.

## 6. Cenários para o BDD e a demonstração

| ID | Cenário | Resultado esperado |
|---|---|---|
| S1 | caminho feliz | OS `FINISHED`, Saga `COMPLETED`, 1 pagamento aprovado |
| S2 | cliente recusa o orçamento | OS `CANCELLED`, orçamento `VOIDED`, estoque restaurado, nenhum pagamento |
| S3 | orçamento expira | idem S2, via timeout |
| S4 | Pix recusado/expirado, nova tentativa paga | OS segue ao passo 5 |
| S5 | Pix esgota as tentativas | OS `CANCELLED`, orçamento `VOIDED` |
| S6 | falha na execução após o pagamento | **estorno** → `CANCELLED`, estoque restaurado |
| S7 | estorno falha | `COMPENSATION_PENDING` + alerta; retry manual conclui |
| S8 | mensagem duplicada / fora de ordem | sem efeito duplicado |
| S9 | pod do OS morto no meio da Saga | retomada pelo estado persistido, sem perda |
| S10 | cancelamento manual após o pagamento | estorno + `CANCELLED` |
| S11 | pagamento aprovado após o cancelamento | estorno automático (`ORPHAN_PAYMENT`) |

## 7. Critérios de aceite

- [ ] Tabela do §4 implementada e coberta por teste de unidade do orquestrador (cada linha, sucesso e falha).
- [ ] S1–S11 automatizados (S1, S2, S6 também em BDD — [09](09-bdd.md)); S9 e S8 exercitados na sessão AWS.
- [ ] Nenhuma Saga fica indefinidamente em estado não terminal sem `deadline_at` (consulta de verificação no teste).
- [ ] Endpoint de consulta da Saga (`GET /admin/sagas/{id}`, `GET /admin/work-orders/{id}/saga`) devolve estado + log de passos.
- [ ] Texto da §1 no README do OS Service e no PDF.

## 8. Fora de escopo

Motor de workflow externo (Step Functions, Temporal); Saga entre OS de clientes diferentes; compensação de e-mails já enviados
(notificações são informativas, não transacionais).
