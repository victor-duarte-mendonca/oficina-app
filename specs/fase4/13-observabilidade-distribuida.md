# Spec 13 — Observabilidade e rastreamento distribuído

> Atende **R14** (usar as ferramentas de monitoramento e observabilidade da Fase 3) e o item de vídeo "monitoramento e rastreamento dos fluxos distribuídos".
> Base: [`05-observabilidade.md`](../05-observabilidade.md) e [ADR 0010](../../docs/adr/0010-observabilidade-otlp-new-relic.md) (New Relic, OTLP, `correlationId`).

## 0. Pré-requisito: dívida da Fase 3

A Fase 3 deixou **não validado** o que esta spec estende: dados no New Relic, NRQL contra dados reais, alertas disparados de
propósito, Synthetics, logs do gateway. **Fechar isso é a primeira tarefa da primeira sessão AWS da Fase 4** (lista em
`specs/PROGRESSO.md`, "Primeira coisa da próxima sessão"); sem dados confirmados, os painéis novos seriam escritos às cegas.

## 1. Rastreamento (traces)

- Cada serviço usa `quarkus-opentelemetry` (OTLP → agente no cluster → New Relic), amostragem 1.0 em hml e 0.2 em prod como na Fase 3 — **exceto** spans de Saga, que ficam com amostragem **100%** (volume baixo, valor alto) via *parent-based* + atributo `saga.id`.
- **Propagação pela fila (o ponto novo):** ao publicar, o produtor grava `traceparent` (W3C) nos atributos da mensagem SQS e no envelope; ao consumir, o `SqsPoller` **extrai o contexto** e abre o span do tratamento como filho (ou *link*, com `messaging.operation=process`). Resultado: **um trace** `gateway → OS → fila → Billing → Stripe → webhook → fila → OS → fila → Execution`.
- Atributos de span obrigatórios: `saga.id`, `saga.step`, `workorder.id`, `workorder.number`, `messaging.system=aws_sqs`, `messaging.destination.name`, `messaging.message.id`; **sem** CPF, e-mail, token ou chave.
- O `correlationId` (cabeçalho `X-Correlation-Id` → MDC → logs) vai no envelope e é restaurado no MDC no consumidor; `traceId`/`spanId` também no MDC (como já feito na Fase 3).
- Webhook da Stripe: o gateway gera um `requestId`; Billing o usa como `correlationId` e **associa** ao `sagaId` pelo pagamento.

## 2. Logs

JSON estruturado nos três serviços com os campos da Fase 3 (`mdc`, `env`, `service.name`) mais `sagaId`, `workOrderId`, `messageId`,
`messageType`, `outcome`. Níveis: transição de Saga em `INFO`; compensação em `WARN`; `COMPENSATION_PENDING`, DLQ e webhook inválido em `ERROR`.
Política **sem dados sensíveis** (CPF completo, token, chave de API e segredo do webhook da Stripe, QR code completo) verificada com `grep` na sessão AWS e por teste de unidade do *logger* de mensagens.

## 3. Métricas (Micrometer → Prometheus → New Relic)

| Serviço | Métrica | Tipo |
|---|---|---|
| OS | `oficina_saga_started_total`, `oficina_saga_completed_total`, `oficina_saga_compensated_total{reason}`, `oficina_saga_compensation_pending` (gauge), `oficina_saga_duration_seconds{outcome}` (timer), `oficina_saga_step_duration_seconds{step}` | contador/gauge/timer |
| OS | `oficina_outbox_pending` (gauge), `oficina_outbox_lag_seconds` | gauge |
| todos | `messaging_consumed_total{queue,outcome}`, `messaging_processing_seconds{queue}`, `messaging_dlq_sent_total{queue}` | contador/timer |
| Billing | `billing_payments_total{status}`, `billing_payment_amount_sum`, `billing_webhook_received_total`, `billing_webhook_invalid_signature_total`, `billing_provider_request_seconds{operation,outcome}`, `billing_refunds_total{outcome}` | contador/timer |
| Execution | `execution_queue_depth{phase}` (gauge), `execution_task_duration_seconds{phase}`, `execution_failures_total` | gauge/timer/contador |
| SQS (CloudWatch) | `ApproximateNumberOfMessagesVisible`, `ApproximateAgeOfOldestMessage` por fila e DLQ | via integração AWS ou consulta periódica |

Anotação `newrelic.io/scrape` nos Deployments novos (como corrigido na Fase 3).

## 4. Dashboards (Terraform `observability/` em `oficina-infra-k8s`)

Novos, além dos 2 da Fase 3 (que continuam):

1. **Saga** — iniciadas × concluídas × compensadas (série), duração P50/P95/P99 por desfecho, funil por passo, `COMPENSATION_PENDING`.
2. **Mensageria** — profundidade/idade de cada fila e DLQ, vazão, tempo de processamento, reentregas.
3. **Pagamentos** — por status, tempo até `PaymentApproved`, falhas da Stripe, estornos, webhooks inválidos.
4. **Fila de execução** — profundidade por fase, tempo em fila, tarefas por mecânico (sem dados pessoais além do `sub`).

Cada dashboard com *filtro por `workOrderId`/`sagaId`* para o roteiro do vídeo (seguir uma OS).

## 5. Alertas (NRQL)

| Alerta | Condição | Severidade |
|---|---|---|
| DLQ com mensagens | `dlq` visíveis > 0 por 1 min | crítico |
| Saga em `COMPENSATION_PENDING` | gauge > 0 por 1 min | crítico (dinheiro retido) |
| Saga travada | OS em estado não terminal sem avanço > limite do passo | alto |
| Outbox atrasado | `oficina_outbox_lag_seconds` > 30 s por 2 min | alto |
| Falhas de pagamento | taxa de `PaymentRequestFailed` > 20% em 5 min | alto |
| Webhook com assinatura inválida | > 5 em 5 min | médio (possível ataque) |
| Stripe lenta/fora | P95 `billing_provider_request_seconds` > 5 s ou erro > 10% | alto |
| Disponibilidade/CPU/memória/reinicializações | as da Fase 3, estendidas aos 3 serviços | conforme Fase 3 |

Notificação por e-mail (mesmo canal da Fase 3). Cada alerta **disparado de propósito ao menos uma vez** (derrubar um consumidor para a DLQ; forçar falha de estorno).

## 6. Critérios de aceite

- [ ] Pelo menos **uma OS** com trace único atravessando OS → Billing → OS → Execution → OS (print/URL no README e no vídeo).
- [ ] `traceId` e `correlationId` iguais nos logs dos três serviços para a mesma OS (consulta no New Relic Logs).
- [ ] Métricas do §3 visíveis no New Relic com dados (`FROM Metric SELECT ... WHERE metricName LIKE ...`).
- [ ] 4 dashboards novos com dados reais; NRQL conferidas contra dados reais (lição da Fase 3).
- [ ] Alertas do §5 criados pelo Terraform; ao menos DLQ, `COMPENSATION_PENDING` e falha de pagamento disparados e recebidos por e-mail.
- [ ] Nenhum dado sensível nos logs/spans (grep + teste).
- [ ] Custo: ingestão estimada dentro do *free tier* de 100 GB/mês (medida na sessão).

## 7. Fora de escopo

Métricas de negócio de BI; retenção longa; SLO formais/error budget; *profiling* contínuo.
