# Progresso da Fase 4

> Fonte única do estado da Fase 4. Ler no início da sessão; atualizar ao fim de cada spec concluída.
> Plano e rastreabilidade requisito → spec: [`00-visao-geral.md`](00-visao-geral.md). O estado da Fase 3 continua em
> [`../PROGRESSO.md`](../PROGRESSO.md).
> Última atualização: **2026-11-24** (sessão 12e — Billing e Execution publicados; #37 e #38 mergeados).

## Estado por spec

Legenda: ✅ concluída e testada · 🟡 parte do OS concluída, falta outro serviço ou AWS · ⬜ não iniciada.

| Spec | Tema | Estado | O que falta |
|---|---|---|---|
| 01 | Fronteiras e propriedade de dados | 🟡 | criar os 2 repositórios (usuário); ArchUnit nos 3 serviços |
| 02 | SQL × NoSQL | 🟡 | **OS: V7 ✅**; RDS do Billing e DynamoDB do Execution (F3/F4 + F2) |
| 03 | Mensageria e contratos | 🟡 | **OS: contratos, outbox, inbox, relay, consumidor ✅**; lado Billing/Execution; filas reais (F2); continuar o trace no consumo (spec 13) |
| 04 | Saga orquestrada | 🟡 | **orquestrador, compensações, timeouts e órfãos ✅ (S1, S2, S5–S11 em unidade; S1, S6, S8, cancelamento e timeout em integração)**; S9 (pod morto) na sessão AWS; README |
| 05 | Billing + Stripe | 🟢 | **código, 154 testes, Docker, K8s, CI ✅ em `../oficina-billing-service` (`homolog`); adapter Stripe + estorno pendente + `scripts/e2e-stripe.ps1` mergeados; E2E local com a Stripe real passou (Pix, webhook, estorno)**; falta: URL do webhook no deploy (S3), chave/endpoint de `prod`, RDS (D3) |
| 06 | Execution Service | 🟡 | **código, testes (66; 97,3%/84,9%), Docker, K8s, CI ✅ em [`oficina-execution-service`](https://github.com/victor-duarte-mendonca/oficina-execution-service)**; rulesets (usuário); deploy com a infra da spec 12 |
| 07 | Refatoração do OS | 🟡 | **Strategy de fluxo, transições da Saga, histórico, rotas de Saga, 410 ✅**; `sagaState` público; Postman; V8 |
| 08 | Testes e cobertura | 🟡 | **OS: gate BUNDLE (80% linhas / 70% ramos) ✅ — 94,0% / 79,6%**; Billing/Execution; ArchUnit |
| 09 | BDD | 🟡 | **OS: Cucumber S1, S2, S5, S6, S7 ✅ (5 cenários, 74 passos, reprova o build)**; BDD de serviço no Billing/Execution; ponta a ponta com os 3 serviços |
| 10 | SonarCloud | ⬜ | **organização e `SONAR_TOKEN` (usuário)** |
| 11 | CI/CD por serviço | ⬜ | pipelines dos repos novos; checks novos no ruleset |
| 12 | Infra (SQS, DynamoDB, RDS, gateway, IMDS) | ⬜ | Terraform; spike de capacidade do nó; **sessão AWS (usuário)** |
| 13 | Observabilidade distribuída | 🟡 | **métricas da Saga e `traceparent`/`correlationId` no envelope ✅**; extração no consumo, dashboards, alertas, dívida E7 |
| 14 | Documentação e entregáveis | ⬜ | READMEs, diagramas, Postman, PDF (usuário), vídeo (usuário) |

## Checklist do que foi entregue (sessão 12, branch `feature/f4-os-saga`)

**Spec 03 — contratos**
- ✅ `contracts/`: envelope + 3 catálogos por produtor (JSON Schema 2020-12) + exemplos válidos de todos os 20 eventos + 6 inválidos
- ✅ `ContractSchemaTest` (consumidor): todo exemplo valida no schema **e** é decodificado/traduzido pelo OS; o catálogo inteiro tem tradutor
- ✅ `SagaCommandContractTest` (produtor): os 6 comandos + `DiagnosisStarted`, gerados pelo código real, validam no schema; sem CPF no fio
- ✅ **Bug real achado pelo teste:** comando de Saga sem `sagaId` passava no schema (`properties` não exige presença) → `required` adicionado

**Spec 02/07 — OS**
- ✅ V7 aplicada em PostgreSQL 16 real: status `AWAITING_PAYMENT`/`AWAITING_EXECUTION`, `queued_for_execution_at`, `work_order_status_history`
  (com backfill), `saga_instance` (índice único parcial = 1 Saga viva por OS), `saga_step_log`, `outbox`, `inbox`
- ✅ `WorkOrder`: `submitForBudget`, `revertToDiagnosis`, `awaitPayment`, `queueForExecution`, `startExecution` (só aceitam a origem correta)
- ✅ Strategy `ApprovalWorkflow` (Fase 3 × Saga) por `APP_SAGA_ENABLED` (desligada por padrão; ConfigMap atualizado, `kubectl kustomize` ok)
- ✅ Rotas: `GET /admin/work-orders/{id}/history`, `GET /me/work-orders/{id}/history` (404 para OS alheia, sem nome de funcionário),
  `GET /admin/work-orders/{id}/saga`, `GET /admin/sagas/{id}`, `POST /admin/sagas/{id}/retry-compensation` (ADMIN); 410 em `approve`/`reject`/`complete`
- ✅ Matriz RBAC: +5 rotas × 4 papéis

**Spec 04 — Saga**
- ✅ Domínio puro com **State pattern** (um handler por estado), pilha de compensações (LIFO), `SagaPolicy`/relógio injetados
- ✅ `WorkOrderSagaTest` (43 casos): cada linha da tabela §4 em sucesso/falha/timeout, compensação parada e retomada, cancelamento manual, órfãos
- ✅ `SagaFlowIntegrationTest` (7): caminho feliz até `FINISHED`; falha na execução → estorno → anulação → `CANCELLED` com estoque devolvido;
  mensagem duplicada; cancelamento manual com Saga ativa; 410; timeout pelo job; resposta desconhecida
- ✅ `SqsMessageTransportTest` contra **LocalStack**: FIFO, grupo por OS, deduplicação pelo `messageId`, confirmação apaga

**Suíte:** `mvn verify` **590 testes verdes** (eram 431), gate por pacote + BUNDLE atendidos.

## Checklist da sessão 12b (branch `feature/f4-bdd-saga`, PR #37 para `homolog`)

**Spec 09 — BDD**
- ✅ `features/saga/ciclo-da-ordem-de-servico.feature` (pt-BR): S1 fluxo completo, S2 recusa, S5 Pix esgotado, S6 falha com estorno, S7 estorno recusado → intervenção
- ✅ Cucumber 7.34.7 rodando dentro do `@QuarkusTest` (`SagaBddTest`): aplicação, PostgreSQL e perfil reais; Billing/Execution simulados pelo contrato
- ✅ Prova negativa: passo indefinido e expectativa errada reprovam o `mvn verify`
- ✅ Fixture `SagaScenario` compartilhado com o `SagaFlowIntegrationTest` (sem duplicação de montagem)
- ✅ README: seção "BDD — ciclo da OS na Saga" (como rodar e onde está o relatório)
- ✅ `mvn verify`: **591 testes** (o BDD conta 1, com 5 cenários), cobertura do módulo 94,1% linhas / 79,8% ramos

## Checklist da sessão 12c (repositório local `../oficina-execution-service`, branch `main`)

**Spec 06 — Execution Service**
- ✅ Domínio `ExecutionTask` (DIAGNOSIS/REPAIR; QUEUED → IN_PROGRESS → FINISHED/FAILED; CANCELLED), comandos da Saga idempotentes
- ✅ Strategy por comando + Unit of Work atômico no DynamoDB (tarefa com versão, notas, outbox, inbox numa `TransactWriteItems`)
- ✅ SQS: consumidor que só confirma após gravar; relay do outbox (GSI2 esparso) que para na primeira falha
- ✅ API `/admin/executions` (ADMIN/MECHANIC), filtro `X-Gateway-Key`, correlação, erros sem stacktrace
- ✅ Testes: domínio, casos de uso (memória), contrato (produtor e consumidor), **LocalStack DynamoDB + SQS**, API com JWT real
- ✅ `mvn verify`: 66 testes, **97,3% linhas / 84,9% ramos** (o gate reprovou em 65% de ramos antes dos testes de filtro/payload)
- ✅ `Dockerfile`, `k8s/` (kustomize hml/prod ok), `ci.yml` (actionlint e shellcheck ok), `docker-compose.yml` com LocalStack, README
- ✅ Repositório [`oficina-execution-service`](https://github.com/victor-duarte-mendonca/oficina-execution-service) criado pelo usuário;
  `main` e `homolog` publicadas (2026-11-23)
- ⬜ Rulesets de `main`/`homolog` e Environments — **usuário** (como na E0 da Fase 3)

## Checklist da sessão 12d (repositório local `../oficina-billing-service`, branch `main`)

**Spec 05 — Billing Service**
- ✅ Domínio `Budget` (SENT → APPROVED/REJECTED/EXPIRED → VOIDED; decisão idempotente; token em hash, tempo constante) e
  `Payment` (REQUESTED → PENDING → APPROVED/REJECTED/EXPIRED; FAILED; REFUND_PENDING → REFUNDED/REFUND_FAILED; órfão tardio)
- ✅ Strategy por comando; chave de idempotência gravada antes do provedor; chamada HTTP fora de transação
- ✅ PostgreSQL (Flyway V1): índices únicos parciais — **teste de concorrência com 2 transações: só 1 pagamento ativo**
- ✅ `PaymentGatewayPort` (rascunho com Mercado Pago, a ser trocado pela Stripe): Mercado Pago (Payments API, retry + circuit breaker, timeouts 3 s/10 s) e simulado (dev/hml/testes)
- ✅ Webhook: 16 KB, assinatura (fail closed), só o `data.id`, reconsulta, desconhecido = 200 — corpo adulterado não muda nada
- ✅ Reconciliação (Pix pendente > 2 min; prazo vencido; pedido sem resposta > 5 min) e expiração de orçamento (`SKIP LOCKED`)
- ✅ API: link do e-mail (404 igual para token errado e OS inexistente), área do cliente, atendimento, Pix do cliente, simulação
- ✅ `mvn verify`: **138 testes, 94,7% linhas / 85,3% ramos**; actionlint, shellcheck, kustomize e gitleaks ok
- ✅ Smoke `docker compose` (perfil prod): fila → `BudgetGenerated` → aprovação pelo link → `PaymentRequested`
- ✅ Repositório [`oficina-billing-service`](https://github.com/victor-duarte-mendonca/oficina-billing-service) publicado (`main` e `homolog`, 2026-11-24), ruleset igual ao dos demais
- ✅ OS: com a Saga ligada, o e-mail de orçamento é só o do Billing (correção no PR #38)

## Não testado por depender de AWS ou de credenciais

- **Stripe (modo de teste)** (conta com Pix ativo + credenciais do usuário; adapter ainda não escrito): criação do Pix, webhook assinado de verdade, Pix expirado (S5),
  aprovação de teste (V4), estorno real. O adapter foi testado contra respostas no formato da documentação.

- SQS **real** na conta Academy (só LocalStack) e a permissão do `LabRole` para SQS — sessão AWS (spec 12).
- Credenciais dos pods via IMDS (`hop_limit`, ADR 0022) — não implementado ainda; exige o cluster.
- `traceparent` com tracing ligado (o SDK OTel fica desligado em teste; o envelope grava `null`) — New Relic na sessão AWS.

## Decisões tomadas nesta sessão (registradas nas specs/ADRs)

- **D2 = SQS** e **D4 = nomes padrão** assumidos (o usuário não se opôs); D1 e D3 seguem abertas.
- SDK AWS v2 direto em vez da extensão Quarkiverse (ADR 0020, spec 03 §5).
- `send-for-approval` continua síncrono (OS vai na hora para `AWAITING_APPROVAL`); fluxo legado detectado pela ausência de Saga (spec 07 §3/§5).
- Colunas explícitas em `saga_instance` no lugar de `context` JSONB (spec 02 §2).

## Próximo passo

> Lição (2026-11-24): ruleset aplicado num repositório **vazio** bloqueia a criação de `main`/`homolog` (os checks obrigatórios
> valem na criação). Criar as branches antes do ruleset, ou desativá-lo durante o primeiro push.

1. Usuário: reativar o ruleset do `oficina-billing-service` (desativado só para o primeiro push); Environments dos repositórios
   novos quando chegar o deploy (spec 11).
2. Usuário: (chave `oficina-billing-hml` já regravada no SSM e validada em 2026-11-26) repetir chave `oficina-billing-prod` + endpoint de webhook para `prod`; token do SonarCloud; decisões D3 e D1.
3. Claude: **spec 11** (pipelines e deploy dos 3 serviços, com o passo que atualiza a URL do endpoint `we_1Ug1nePktTZwDP3dD4YYBj2X` a cada sessão do Academy — S3 — e injeta `STRIPE_*` do SSM no Secret do Billing), **spec 12** (Terraform: filas, DynamoDB, banco do Billing, rota `/webhooks/stripe` sem JWT, egress a `api.stripe.com`, IMDS) e spec 10 (Sonar).
   O E2E local com a Stripe real já passou (`scripts/e2e-stripe.ps1` no Billing).

## Desafios e lições da sessão de 2026-11-26 (Mercado Pago → Stripe, sessões 12f–12h)

| Desafio | Causa | Como superamos / regra daqui em diante |
|---|---|---|
| Mercado Pago sem autenticação | credenciais do sandbox não funcionaram | Troca para a Stripe **só no adapter** (a porta `PaymentGatewayPort` isolou o domínio): ADR 0023, spec 05 §4–§6, `StripeApi`/`StripePaymentGateway`/`StripeSignature`, Flyway V2; Mercado Pago removido |
| Pix "habilitado" no Dashboard mas `capabilities.pix_payments=inactive` | em sandbox o campo da conta não reflete o que a API aceita | A prova é um PaymentIntent real (Stripe CLI), não o campo `capabilities` (S1, S2 resolvidos) |
| MCP da Stripe não cria PaymentIntent | só expõe leituras desse recurso | Escrever com o **Stripe CLI** (`stripe payment_intents create`, `stripe refunds create`); ler/validar pelo MCP |
| `payment_method_types` recusado e `return_url` exigido | a API só aceita métodos dinâmicos (Dashboard) | `automatic_payment_methods[enabled]=true` + `allow_redirects=never` + `payment_method_data[type]=pix`. `stripe trigger payment_intent.succeeded` quebra pelo mesmo motivo: gerar evento com `pm_card_visa` |
| Estorno de Pix nasce `pending` | o adapter tratava qualquer 2xx como estorno concluído | `RefundOutcome` na porta; `findRefund` (`GET /v1/refunds?payment_intent=`); `REFUND_PENDING` só conclui por `refund.updated`/reconciliação de 60 s; falha vira `PaymentRefundFailed` |
| Webhook "invisível" na primeira execução | `stripe listen` morreu com `Permission denied`: o Stripe CLI lê a variável `STRIPE_API_KEY` como a chave **dele** e recebeu a chave restrita do Billing | A chave do Billing vai ao compose como `BILLING_STRIPE_API_KEY`; o script limpa `STRIPE_API_KEY`. **Nunca exportar chave do Billing com o nome `STRIPE_API_KEY`** |
| Chave de API vazou em print/histórico | o usuário colou a chave dentro de `Read-Host "…"` (o texto entre aspas é só o prompt) | Padrão seguro: o script pede a chave com `Read-Host -AsSecureString` (usuário só cola no prompt). Chave exposta = **rolar** (Dashboard → *Alternar chave*) + `Clear-History` + apagar `(Get-PSReadLineOption).HistorySavePath`. Nunca repetir o valor |
| SSM com valor errado | `put-parameter --value (Get-Clipboard)` gravou o texto de um comando (a área de transferência mudou) | Validar o prefixo (`rk_test_`) antes de gravar e conferir só prefixo+tamanho. **Resolvido em 2026-11-26: o usuário regravou `/oficina/hml/stripe_api_key` com a chave nova e os comandos funcionaram** |
| `.ps1` quebrando no PowerShell 5.1 | acentos sem BOM, stderr de programa nativo vira erro fatal, `try` não é expressão | Script só em ASCII; `$ErrorActionPreference="Continue"` + `Step`/`throw`; `2>&1` e regex tolerantes à codificação |
| Código de aprovação não achado no log | log do mailer em JSON, console com acentos trocados e logs de execuções anteriores | Filtrar pela OS do run e usar `C.{1,3}digo de autoriza.{1,6}o:` |
| Edição por script desfez regex | `perl -pi` dentro de aspas duplas transforma `\w` em `w`; arquivos do Windows com CRLF e LF misturados | Conferir o resultado do `perl`; `sed -i 's/\r$//'` antes de editar multilinha; preferir a ferramenta Edit |
| Commits depois do merge não entraram na `homolog` | o PR já tinha sido mergeado quando continuei na mesma branch | Depois de um merge, abrir branch nova a partir de `origin/homolog` (ou cherry-pick) e conferir `gh pr view` antes de dizer que um PR está aberto |
| Docker Desktop parado | — | `Start-Process "C:\Program Files\Docker\Docker\Docker Desktop.exe"` e esperar `docker version`; projeto Compose próprio, `stop` (nunca `down -v`) |

Estado ao fim da sessão: **E2E local com a Stripe real passou** (`oficina-billing-service/scripts/e2e-stripe.ps1`:
orçamento → aprovação → Pix → *Simulate scan* → webhook `APPROVED` → estorno `REFUNDED`). Tudo mergeado em `homolog`
(`oficina-app#40–#42`, `oficina-billing-service#2–#4`); Billing com 154 testes. Ainda **não** feito: deploy do webhook no
Academy (S3), chave/endpoint de `prod`, specs 10–12.

## Histórico

| Sessão | Data | Feito |
|---|---|---|
| 11 | 2026-11-22 | specs 01–14, ADR 0022, PR #34 (docs) e #35 (fix do Docker no CI) |
| 12 | 2026-11-23 | OS Service da Fase 4: contratos, V7, Saga, outbox/inbox, histórico, rotas, 590 testes (PR #36) |
| 12b | 2026-11-23 | Spec 09: BDD da Saga em Cucumber (S1, S2, S5, S6, S7), 591 testes (PR #37; #36 mergeado) |
| 12c | 2026-11-23 | Spec 06: Execution Service completo em repositório local (DynamoDB, SQS, API, 66 testes, Docker, K8s, CI) |
| 12d | 2026-11-23 | Execution publicado no GitHub; spec 05: Billing Service em repositório local (PostgreSQL, Pix simulado + adapter MP, webhook, 138 testes) |
| 12e | 2026-11-24 | PRs #37 e #38 mergeados; Billing publicado no GitHub (`main`/`homolog`), rulesets nos repos novos |
| 12f | 2026-11-26 | Mercado Pago -> Stripe (Pix, modo de teste): MCP validado (conta BR de teste acessivel, Pix inativo), ADR 0023, spec 05 §4-§6 reescritos, demais specs ajustadas |
| 12g | 2026-11-26 | Adapter Stripe no Billing (`oficina-billing-service#2`): gateway, assinatura, webhook, V2 `provider_payment_id`; 148 testes; Mercado Pago removido |
| 12h | 2026-11-26 | Pix testado no sandbox (pago via Simulate scan; estorno `pending` -> `succeeded`; webhook assinado 200). Correcao do estorno pendente e script e2e (`oficina-billing-service#3`, 154 testes)  E2E local com a Stripe real (`scripts/e2e-stripe.ps1`): orçamento, aprovação, Pix, Simulate scan, webhook `APPROVED`, estorno `REFUNDED` -- tudo passou. |

| 12i | 2026-11-26 | Lições da troca para a Stripe documentadas (tabela de desafios acima); `CLAUDE.md` do workspace atualizado |
