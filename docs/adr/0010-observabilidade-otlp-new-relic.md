# ADR 0010 — Observabilidade por OTLP com New Relic como backend intercambiável

- **Status:** Aceito
- **Origem:** [`specs/05-observabilidade.md`](../../specs/05-observabilidade.md); implementação nos PRs da E7

## Contexto

O requisito R7 pede latência das APIs, CPU/memória do Kubernetes, healthcheck/uptime, alertas de falha no processamento de OS,
logs JSON com correlação e dashboards. Os Lambdas rodam em VPC sem NAT (não têm internet) e o node é único e pequeno.

## Decisão

**New Relic** como backend, com a **instrumentação neutra a fornecedor**:

- **Traces:** `quarkus-opentelemetry` exporta por **OTLP** direto do pod. Desligado por padrão; liga com `NEW_RELIC_LICENSE_KEY` no
  Secret. Amostragem `parentbased_traceidratio`: 1.0 em hml, 0.2 em prod. Trocar de fornecedor é mudar endpoint e header.
- **Métricas:** `/q/metrics` (Micrometer) coletado pelo `newrelic-prometheus-agent`, sem tocar na aplicação. As métricas de negócio
  (`oficina_work_orders_created_total`, `..._transition_errors_total`, `..._integration_failures_total`, `..._auth_denied_total`)
  alimentam dashboards e alertas.
- **Logs:** stdout JSON do pod por Fluent Bit (`newrelic-logging`); logs do gateway e dos Lambdas via CloudWatch + um **forwarder**
  fora da VPC (Node 20) que os envia à Log API.
- **Cluster:** chart `nri-bundle` instalado pelo HelmChart nativo do k3s, com `lowDataMode` e requests/limits de memória.
- **Como código:** dashboards (JSON versionado), 6 políticas de alerta e monitor Synthetics em Terraform (`newrelic`).

## Consequências

- (+) Free tier perpétuo de 100 GB/mês cobre os três sinais; custo adicional ≈ US$0,05.
- (+) A troca de backend não reinstrumenta a aplicação (só os traces são OTLP; métricas e logs seguem formatos padrão).
- (−) **Só os traces são OTLP:** métricas via scrape Prometheus e logs via Fluent Bit/forwarder foram escolhas pragmáticas (exportador
  de métricas OTLP do Quarkus 3.15 ainda experimental). A neutralidade é parcial.
- (−) O trace **começa na aplicação**: o HTTP API não propaga W3C; o gateway entra por correlação de logs.
- (−) Os agentes disputam memória com a aplicação no node único; em hml (2 GiB) rodam sem o prometheus-agent.
- (−) Forwarder próprio para manter: o do repositório oficial do New Relic cria IAM role, bloqueado no Academy.

## Alternativas consideradas

- **Datadog:** free tier só de infraestrutura (sem APM nem logs); APM só em trial de 14 dias; integração AWS recomendada exige
  `iam:CreateRole`.
- **Prometheus + Grafana auto-hospedados:** dobrariam o consumo de memória do node único.
- **Agente proprietário do New Relic na aplicação:** menos configuração, mas prende a instrumentação ao fornecedor.
