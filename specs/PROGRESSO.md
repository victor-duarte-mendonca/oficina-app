# Progresso da Fase 3

> Fonte única do estado do projeto. Ler no início da sessão; atualizar ao final.
> Última atualização: **2026-10-28** (sessão 8 — apply manual, E7, E8 e E9 escritos e em PR; falta só a **sessão AWS consolidada em produção**, guiada por `docs/VALIDACAO-PROD.md`).

Workspace local: `C:\Users\victo\Desktop\Projetos\FIAP\` (os 4 repos + `oficina-front/` fora do escopo).

## Etapas (spec 00 §4)

| Etapa | Spec | Repo | Status |
|---|---|---|---|
| E0 Split dos repositórios + proteção + pipelines | 08 | todos | ✅ concluída |
| E1 Modelagem do banco (V5, `clients.status`, pool) | 01 | oficina-app | ✅ concluída |
| E2 Infra K8s (Terraform, workspaces hml/prod, SSM) | 06 | oficina-infra-k8s | ✅ concluída (hml aplicada; prod ainda não) |
| E3 Infra DB (Terraform, módulo completo) | 07 | oficina-infra-database | ✅ concluída (hml aplicada; prod ainda não) |
| E4 App: role CLIENT, ownership, correlação, métricas | 04 | oficina-app | ✅ concluída (código e CI; deploy em cluster pendente) |
| E5 Lambda de autenticação por CPF | 02 | oficina-auth-lambda | ✅ concluída (código + Terraform; validação em AWS pendente) |
| — Deploy da aplicação (pipeline; spec 08 §6) | 08 | oficina-app | ✅ mergeado (app#15); validação em AWS pendente |
| E6 API Gateway + authorizer | 03 | oficina-auth-lambda | ✅ mergeada (#3 e #4); validação em AWS pendente |
| E7 Observabilidade fim-a-fim (New Relic) | 05 | app + infra + lambda | ✅ mergeada (app#19, infra-k8s#8 e #9, auth-lambda#5); validação em AWS pendente |
| E8 Documentação arquitetural (ADRs, diagramas, READMEs) | 09 | todos | 🟡 escrita: 17 ADRs, 3 RFCs, diagramas em `docs/` e seção Documentação nos READMEs (PRs abertos); revisar links após promover para `main` |
| E9 Runbook de sessão e custos | 10 | oficina-app (`docs/`) | 🟡 escrito (`RUNBOOK.md`, `orcamento.md`, `VALIDACAO-PROD.md`, `scripts/k3s-exec.sh`); **execução ponta a ponta e medições de tempo/custo pendentes (sessão AWS)** |

## Próximo passo

**Todo o código de E0–E7 está escrito e MERGEADO em `homolog` nos 4 repositórios, e a documentação (E8) e o runbook (E9) estão escritos.** Falta a
**sessão AWS consolidada, em produção (`main`, workspace `prod`)**, que valida todos os ⬜ do checklist de uma vez. **Nada está de pé na AWS.**

> **Guia único da sessão: [`docs/VALIDACAO-PROD.md`](../docs/VALIDACAO-PROD.md)** (decisões, secrets, promoção `homolog` → `main`, roteiro passo a passo,
> checklist com comandos e planos B). Operação e custo: [`docs/RUNBOOK.md`](../docs/RUNBOOK.md) e [`docs/orcamento.md`](../docs/orcamento.md).

**Antes da sessão (do usuário, sem AWS)**
1. **Decidir D1–D5** (`VALIDACAO-PROD.md` §1). **D1 decidida: seed LIGADO em `prod`** (PR `feature/prod-seed-validacao`; o deploy exige `APP_SEED_ADMIN_PASSWORD` e `APP_SEED_MECHANIC_PASSWORD` em `producao`). **Reverter para `"false"` depois da gravação do vídeo.** Restam D2–D5.
2. **Mergear** os PRs abertos de documentação (app, auth-lambda, infra-k8s, infra-database) e depois **não** promover para `main` ainda: a promoção
   `homolog` → `main` **dispara o deploy de produção**; fazê-la **durante a sessão**, repositório a repositório, na ordem de subida.
3. **Secrets do Environment `producao`** (tabela completa em `VALIDACAO-PROD.md` §3.1) e **um** par JWT (`oficina-app/generate-keys.sh`, a mesma chave na app e no
   Lambda). Conta New Relic (D2) é opcional: sem ela nada quebra, a E7 só não é validada.
4. Conferir o `ALLOWED_CIDR` (seu IP mudou?) e ter `aws` CLI, `gh`, `hey` e Postman.

**Na sessão** (4 h, ≈ US$ 0,31): `set-aws-session-secrets.sh` → infra-k8s → infra-database → app → auth-lambda → (Observability) → conferências → carga/HPA → **destroy na
ordem inversa** + varredura de órfãos. `scripts/k3s-exec.sh prod "<kubectl ...>"` dá acesso ao cluster por SSM (sem SSH, sem expor a 6443).

**Se algo falhar:** `gh run view <id> --log-failed`; planos B por risco em `VALIDACAO-PROD.md` §7 (`apigateway:*`, `LabRole`, SnapStart, `reserved_concurrent_executions`,
`ssm:GetParameter` do instance profile, memória do node). Correções vão por `feature/x` → `homolog` → `main`.

**Fluxo de PRs (spec 08 §2):** `feature/x` → PR para `homolog` → valida → PR `homolog` → `main`. Nunca feature direto para `main`.

## Checklist de validação por etapa

Legenda: ✅ verificado · ⬜ pendente (precisa de AWS ou de cluster) · ⚠️ confirmado pelo usuário, sem evidência registrada.

**E0 — repositórios e proteção**
- ✅ 4 repositórios públicos na org, histórico sem credenciais, rulesets `main`/`homolog` (PR + squash + checks, sem bypass), environments `homologacao`/`producao`
- ⬜ `soat-architecture` com acesso aos 4 repositórios

**E1 — banco (V5/V6)**
- ✅ migrations V5/V6 executadas contra **PostgreSQL 16 real** (Testcontainers); suíte passou de 265 para 429 testes
- ✅ bugs reais corrigidos (CHECK de status, `app_users.updated_at`, nome do banco, placeholder do Flyway, `order_number`)
- ⬜ migrations aplicadas num **RDS real** (acontece quando a app sobe em `hml`)

**E2 — infra K8s**
- ✅ `fmt`/`validate`/`tflint`/`trivy`/`actionlint` · ✅ `apply` em `hml` (39 recursos) · ✅ 6 parâmetros SSM · ✅ `destroy` e conferência de restos
- ⚠️ nó k3s `Ready` via túnel SSM · ⬜ `apply` em `prod` · ⬜ `apigateway:*` liberado · ⬜ `LabRole` aceita `lambda.amazonaws.com` e tem permissão de ENI

**E3 — infra do banco**
- ✅ `fmt`/`validate`/`tflint`/`trivy` · ✅ `apply` em `hml` (RDS privado, criptografado) · ✅ 4 parâmetros SSM · ✅ `destroy`
- ⬜ conexão a partir de um **pod** com `sslmode=require` e **recusa sem TLS** · ⬜ `apply` em `prod` (`DB_PASSWORD` de `producao` já definido)

**E4 — app (CLIENT, ownership, correlação, métricas)**
- ✅ `mvn verify` 429 testes + gate JaCoCo ampliado · ✅ matriz RBAC rota×papel (80 casos, validada por mutação) · ✅ CI verde · ✅ `kubectl kustomize` dos dois overlays
- ✅ aplicação aceita um token emitido pela função E5 (`/me` → CLIENT, `/admin` → 403, adulterado → 401)
- ⬜ `kubectl apply` em cluster · ⬜ `X-Correlation-Id` e `mdc` no **log JSON do pod** (perfil `prod`) · ⬜ `/q/metrics` no pod · ⬜ coleção Postman contra o gateway

**E5 — função de autenticação**
- ✅ 100 testes (unidade, contrato HTTP, **PostgreSQL real**) · ✅ papel somente-leitura recusa `INSERT`/`UPDATE`/`DELETE` e leitura de e-mail/telefone · ✅ 401 byte a byte idêntico
- ✅ CI verde (`build-test`, `terraform-check` com `tflint`/`trivy`/`shellcheck`) · ✅ build reprodutível por Docker (JAR 3,7 MB) · ✅ PRs #1 e #2 mergeados
- ✅ `plan` na CI chega a ler a AWS (falha só por `/oficina/hml/*` inexistente — esperado com `hml` destruída) · ✅ `apply` para no guard dos secrets sem criar nada
- ⬜ `plan`/`apply` reais · ⬜ **SnapStart** na conta Academy e cold start < 1 s (`Restore Duration`) · ⬜ `reserved_concurrent_executions = 10` aceito (senão `-1`)
- ⬜ smoke test do alias (400 / 401 com banco alcançado) · ⬜ nenhum CPF completo no CloudWatch · ⬜ token do Lambda aceito pela app **em execução**

**Deploy da aplicação (oficina-app#15)**
- ✅ `actionlint`/`shellcheck` limpos · ✅ `kubectl kustomize` dos overlays (sem Secret, imagem injetada) · ✅ no push sem infra o deploy passa verde com aviso (verificado em `homolog`)
- ✅ **smoke test rodado de verdade** contra a app no `docker-compose` (PostgreSQL real, V5/V6 migradas, seed, filtro do gateway): 11 verificações OK; cenários negativos (chave errada, senha errada) reprovam
- ✅ no mesmo banco, o papel `oficina_auth_ro` existe, lê e é negado em `email` e `INSERT` (premissa da E5 confirmada)
- ⬜ imagem enviada ao ECR com tag = SHA · ⬜ `kubectl apply -k` no k3s pelo túnel SSM · ⬜ rollout, **rollback** automático · ⬜ health pelo Ingress de dentro do node
- ⬜ Flyway (V1–V6) num **RDS real** com `sslmode=require` e `rds.force_ssl=1` (fecha o ⬜ de E1 e de E3) · ⬜ `X-Correlation-Id` e `mdc` no log JSON do **pod**

**E6 — API Gateway e authorizer (oficina-auth-lambda#3 e #4, mergeados)**
- ✅ `terraform-check` completo no **runner** (fmt, validate, tflint, trivy, shellcheck), `build-test` (178 testes), `workflow-lint` e `plan` (sessão inativa = aviso) · ✅ `stack` pula o `apply` com aviso no merge
- ✅ authorizer: 78 testes (alg `none`, confusão de algoritmo HS256 com a chave pública, RS384/PS256, `jwk` no header, payload adulterado, expirado, `nbf`, emissor, sem `sub`/`exp`) e gate JaCoCo
- ✅ contrato entre as funções (token da `auth-function` aceito pelo authorizer; adulterado → `BAD_SIGNATURE`) · ✅ `terraform validate`/`tflint`/`trivy` · ✅ `actionlint`/`shellcheck`
- ✅ CPFs gerados pelo `smoke-gateway.sh` (40/40 válidos na validação real) · ✅ build reprodutível em Docker gera os dois JARs
- ⬜ `apigateway:*` e a política do log group na conta Academy · ⬜ SnapStart nas duas funções e cold start < 1 s · ⬜ **401 sem header, 403 com token recusado** (comportamento da AWS; a spec 03 §9 supõe 401)
- ⬜ access log do gateway com `integrationLatency` vazio nas requisições negadas · ⬜ throttling → 429 em `POST /auth/token` · ⬜ **fluxo completo do cliente pelo gateway** (cria cliente → `/auth/token` → `/me`)
- ⬜ `curl` direto no EIP sem `X-Gateway-Key` → 403 (manual, do seu IP) · ⬜ coleção Postman contra o gateway

**E7 — Observabilidade (código escrito; 4 PRs abertos: oficina-app#19, infra-k8s#8 e #9, auth-lambda#5)**
- ✅ app: `quarkus-opentelemetry` (desligado por padrão), amostragem 1.0 hml / 0.2 prod, `traceId`/`spanId` no MDC (teste), senha de login falho **não** vai a nenhum log (teste), `correlationId` aceita `=` · ✅ suíte completa 431/431
- ✅ infra-k8s: `nri-bundle` 8.0.39 renderizado com `helm template` (hml e prod), todo container com request/limit de memória; `user_data` renderizado e `bash -n`; terraform/tflint/trivy/actionlint limpos
- ✅ `observability/` (Terraform `newrelic`): validate, 2 dashboards (11 widgets, JSON válido), 6 políticas/8 condições; ✅ log-forwarder: 7 testes `node --test`
- ⬜ licença New Relic lida do SSM pela EC2 (LabInstanceProfile com `ssm:GetParameter`) e agentes de pé · ⬜ **`kubectl top nodes`/`top pods -A`** com folga para o HPA (hml t3.small sem prometheus-agent)
- ⬜ prometheus-agent coleta `/q/metrics` (anotação `newrelic.io/scrape`) · ⬜ traces OTLP chegam ao New Relic (`FROM Span`) · ⬜ **confirmar as NRQL** contra dados reais (nomes de span, `_sum`/`_count` do timer, `K8sContainerSample.reason`) e corrigir os JSON/`alerts.tf`
- ⬜ dashboards carregados do JSON e com dados · ⬜ 6 políticas existem e **2 disparam de propósito** (derrubar pod; forçar transição inválida) · ⬜ monitor Synthetics em `/health` + alerta de disponibilidade
- ⬜ logs do gateway e dos Lambdas no New Relic com `correlationId` (forwarder; LabRole aceita assinatura/permissão) · ⬜ gateway repassa `X-Correlation-Id` e a app o usa · ⬜ `grep` nos logs: nenhum CPF completo nem token

## Pendências

**Do usuário**
- [x] Mergear oficina-auth-lambda#3 e #4 (feito na sessão 7)
- [x] Mergear os PRs de docs do PROGRESSO (#16 e #17) e os de apply manual (infra-k8s#7, infra-database#2)
- [ ] **Decidir D1–D5** e definir os secrets de **`producao`** (tabela em `docs/VALIDACAO-PROD.md` §3) e gerar **um** par JWT para os dois repos
- [ ] Renovar as credenciais do Academy e rodar `set-aws-session-secrets.sh` no início da sessão AWS (as anteriores expiraram)
- [ ] (Opcional) remover dois volumes Docker deixados pelo teste local do deploy: `docker volume rm oficina_postgres_data oficina_jwt_keys` — contêm só dados de teste (comandos de remoção de volume são barrados para o Claude)
- [ ] Definir `DB_PASSWORD` no environment `producao` de `oficina-infra-database` — **feito** em 2026-10-19; os demais secrets de `producao` só quando for subir `prod`
- [ ] Adicionar `soat-architecture` na org `victor-duarte-mendonca` — pode ficar para o fim da fase
- [ ] Apagar a pasta local `Projetos\tech-challenge-fiap` (tudo já está no GitHub; nada local pendente)

**Do Claude (sem AWS)**
- [x] Religar a base do #4 para `homolog` com rebase (feito)
- [x] Disparo manual de `apply` em `oficina-infra-k8s` e `oficina-infra-database` (feito na sessão 8: infra-k8s#7, infra-database#2); `plan` em PR agora ignora sessão expirada
- [x] Código da E7 escrito (4 PRs abertos); validar só na sessão AWS
- [x] E8 (17 ADRs, 3 RFCs, diagramas) e E9 (runbook, orçamento, roteiro de validação em produção, `k3s-exec.sh`) escritos; PRs abertos
- [ ] Depois do merge: conferir que os links `blob/main` dos READMEs resolvem após promover `oficina-app` para `main`, e preencher `docs/orcamento.md` na sessão
- [ ] (Decisão do usuário) o HTTP API não propaga W3C: aceitar que o trace começa na aplicação, ou avaliar X-Ray no gateway (fora do escopo da spec)
- [ ] (Decisão do usuário) filtro do gateway no Vert.x, para o 403 valer também para anônimos — hoje anônimo recebe 401 (ver "Decisões")

**Validações de primeira sessão AWS** (spec 00 §6, spec 03 §8) — fazer na sessão consolidada:
- [ ] `apigateway:*` liberado na conta Academy e política de log group para o access log do HTTP API
- [ ] `LabRole` com trust policy para `lambda.amazonaws.com` e permissão de ENI na VPC

## Estado atual dos repositórios (fim da sessão 8)

| Repo | Branch local | Conteúdo | Pipeline | Checks obrigatórios |
|---|---|---|---|---|
| `oficina-app` | `homolog`/`main` | app Quarkus com E1+E4 (CLIENT, `/me`, correlação, métricas), `k8s/` em kustomize (base + overlays hml/prod; Secret fora), Postman, `scripts/smoke-test.sh`, `specs/` | `ci.yml` + **`deploy.yml`** (imagem ECR, kustomize via SSM, rollback, smoke test) | `build-test`, `docker-build` (Trivy CRITICAL/fixed) |
| `oficina-infra-k8s` | `main` | E2 completa: VPC, EC2/k3s, ECR, SG (faixas do API Gateway), contrato SSM, workspaces `hml`/`prod`, pipeline com apply/destroy | `terraform.yml` | `validate`, `plan` |
| `oficina-infra-database` | `main` | E3 completa: RDS PG16, parameter group (force_ssl), SG, contrato SSM, workspaces, pipeline com apply/destroy | `terraform.yml` | `validate`, `plan` |
| `oficina-auth-lambda` | `homolog` | E5: `auth-function` (Java 21 + SnapStart) + `terraform/` da função; **E6 (mergeada):** `authorizer-function` e Terraform do API Gateway; `scripts/smoke-test.sh` e `smoke-gateway.sh` | `ci.yml` (`build-test`, `terraform-check`) + `deploy.yml` (package/plan/apply/destroy) | `build-test`, `terraform-check` |

Histórico das Fases 1-2 preservado nos 3 repos derivados; `kubeconfig-raw` e `patch.json` removidos
do histórico. `test-keys/*.pem` em `oficina-app` são fixtures documentadas (não são segredo).

**Proteção (nos 4)**: ruleset `protecao-main-homolog` em `main` e `homolog` — sem push direto,
force-push ou delete; PR obrigatório; squash; checks obrigatórios com branch atualizada; **sem bypass**.
Repo: só squash merge, apaga branch após merge. Push direto testado e recusado (`GH013`).

**Environments (nos 4)**: `homologacao` (só branch `homolog`) e `producao` (só `main`, *required
reviewer* = Victor, self-review permitido). **Secrets existentes**: org `ALLOWED_CIDR` e `AWS_*` (expiram por sessão; repor com `set-aws-session-secrets.sh`); env `homologacao` de `oficina-infra-database`: `DB_PASSWORD`. Demais (app, Lambda, `producao`) ainda não definidos.

**Monorepo** `victorduarte31/tech-challenge-fiap`: arquivado, README com aviso e links (PR#8 mergeado).

## Decisões tomadas na execução (complementam as specs)

| Decisão | Motivo |
|---|---|
| Ruleset com **0 aprovações** (spec 08 pedia 1) | GitHub não permite o autor aprovar o próprio PR; equipe de um travaria. Subir para 1 quando houver segundo revisor (`gh api -X PUT repos/<org>/<repo>/rulesets/<id>`) |
| Pipelines commitadas direto em `main` **antes** do ruleset | Checks precisam existir para virarem obrigatórios; a partir daí tudo via PR |
| `plan` roda só se `AWS_ACCESS_KEY_ID` existir; senão passa com `::notice` | Sem credenciais falharia em todo PR; um job *skipped* contaria como sucesso do mesmo jeito |
| `validate` do `infra-database` é só `fmt` até E3 | `rds.tf` sozinho referencia SG/subnets/vars inexistentes |
| netty `4.1.135` → `4.1.137.Final` em `oficina-app` | CVE-2026-75595 CRITICAL pego pelo gate Trivy na primeira run |
| Script de segredos em `oficina-infra-k8s/scripts/` | É o passo 1 do bootstrap; infra-k8s é o passo 2 |
| Composite action `k3s-kubeconfig` em `oficina-app` | Único repo que precisa dela; quinto repo violaria "exatamente quatro" |
| `specs/` e `PROGRESSO.md` vivem em `oficina-app` | Repo principal; o monorepo não recebe mais commits |
| Testes `@QuarkusTest` rodam em **PostgreSQL 16 via Dev Services** (H2 removido do perfil `test`) | V5/V6 usam recursos só do Postgres (TIMESTAMPTZ, `ALTER ... USING`, índice parcial, `CREATE ROLE`); H2 não executa as migrations e a suíte inteira não subia. Exige Docker (local e CI). `jdbc.url` fixa passou a `%prod`/`%docker` para o Dev Services ativar |
| `order_number` atribuído pelo `WorkOrderRepositoryAdapter` no insert (provisório `TMP-…` → `OS-%06d`) | V5 tornou a coluna NOT NULL, mas o `create` salvava sem número; o formato vive em `WorkOrder.orderNumberFor` |
| Bucket de state `oficina-tfstate-<account-id>`, passado por `-backend-config` | Nome de S3 é global e a conta Academy é reciclada; criado por `bootstrap-state-bucket.sh` (idempotente), usado por infra-k8s e infra-database |
| Observabilidade da app por **porta** (`BusinessMetricsPort`) e correlação no **Vert.x** | Aplicação/domínio sem Micrometer; filtro JAX-RS não enxerga o 401/403 da segurança |
| Cliente abre OS só com `vehicleId`+`notes` | Aceitar serviços/peças deixaria o cliente baixar estoque e fixar preço |
| `destroy` é job do workflow `Terraform` (*Use workflow from: homolog*) | Ambiente `homologacao` só aceita a branch `homolog`; a `main` tem a versão antiga do arquivo |
| Lambda em Java 21 **sem framework** + SnapStart (ZIP, não container) | Escolha do usuário na sessão 6; reaproveita a stack e os padrões de teste; pool JDBC só sob demanda por causa do snapshot |
| Regra 5432 do Lambda no SG do RDS vive em `oficina-auth-lambda` | O SG do Lambda só existe depois do RDS; o infra-database publica `rds_security_group_id` no SSM |
| `plan` das pipelines de infra usa segredos placeholder | Segredos reais são de *environment*; usá-los pediria aprovação de `producao` a cada PR para `main` |
| Deploy da app sob demanda: sem stack ou com sessão expirada, o push **ignora com aviso**; o disparo manual falha | `hml` sobe sob demanda (spec 06 §4.1); merge não pode ficar vermelho por falta de infraestrutura. Mesmo padrão no `deploy.yml` do auth-lambda |
| `secret.yaml` virou `secret.example.yaml`, **fora** do kustomize; a pipeline cria os Secrets | `kubectl apply -k` sobrescreveria o Secret real com os placeholders `CHANGE_ME` |
| Chave pública JWT **derivada** da privada (app: `openssl rsa -pubout`; authorizer: `tls_public_key`) | um único secret compartilhado entre app e Lambda; sem risco de as metades divergirem. Dispensa `JWT_PUBLIC_KEY_PEM` da matriz da spec 08 |
| Composite action `k3s-kubeconfig` filtra o node por `Environment` | hml e prod coexistem com `Project=oficina`; sem o filtro um deploy de hml poderia cair em prod |
| Imagem da app com tag = SHA do commit, nunca `:latest`; Trivy antes do push | resolve o motivo do `AWS-0031` no `.trivyignore` do infra-k8s (ECR mutável) quando o deployment passar a usar a tag |
| **7ª rota `GET /me`** no gateway (além das 6 da spec 03) | `/me/{proxy+}` exige ao menos um segmento e **não casa com `/me`** — o perfil do cliente |
| Token recusado pelo authorizer = **403** do gateway; header ausente = **401** | comportamento do HTTP API com Lambda authorizer de resposta simples; a spec 03 §9 supunha 401. O smoke test aceita 401/403 e prova que a requisição não chega ao cluster |
| Authorizer em módulo próprio, **fora da VPC**, sem JDBC, sem rede e sem segredo (só chave pública) | spec 03 §4: se ele cair, todas as rotas protegidas param — mínimo de coisas que podem quebrá-lo |
| **Correção do E4:** o `GatewayKeyFilter` (JAX-RS) roda **depois** da autorização por papel (RESTEasy Reactive) | anônimo sem token → **401**; token válido sem a chave → **403**. Não vaza nada. Para 403 também a anônimos, o filtro precisa ser do Vert.x (como o de correlação) |
| `docker-compose` passou a levar `LAMBDA_RO_PASSWORD` e `APP_GATEWAY_KEY` | a app não subia no compose desde a E1 (placeholder do Flyway vazio é tratado como ausente) |
- **E7 — agentes por HelmChart do k3s, não `helm_release`** (desvio da spec 06 §2): o API 6443 só é acessível por túnel SSM e a EC2 nem existe quando o provider seria configurado. O `user_data` grava o manifesto `HelmChart` e o Secret da licença em `/var/lib/rancher/k3s/server/manifests`; a licença vem de um SecureString no SSM (`/oficina/<env>/newrelic_license_key`), nunca do `user_data`. Sem a licença o cluster sobe sem agentes. Só vale para **instância nova**.
- **E7 — o job `default` do prometheus-agent só coleta alvos de integrações conhecidas** (`integrations_filter`): a anotação `prometheus.io/scrape` da app não basta; o Deployment ganhou `newrelic.io/scrape: "true"`. hml (t3.small) roda sem prometheus-agent; control plane do k3s e metadata-injection desligados; `global.region` é obrigatório com Secret próprio.
- **E7 — o `$context.requestId` do HTTP API termina em `=`**, que o `SAFE_ID` da app rejeitava (geraria UUID novo): agora aceito, e o gateway passa `X-Correlation-Id` (overwrite). O HTTP API **não** propaga contexto W3C: o trace OTLP começa na aplicação; o gateway entra pela correlação de logs, não como span (a spec 05 §10 pedia "trace único atravessando gateway → aplicação": registrado como limite).
- **E7 — dashboards/alertas em Terraform `newrelic`** numa raiz separada (`oficina-infra-k8s/observability/`, state `oficina/observability/<ws>`), com workflow próprio que **ignora o apply sem sessão AWS ou sem secrets** (`NEW_RELIC_API_KEY`, `NEW_RELIC_ACCOUNT_ID`, `ALERT_EMAIL`). NR só tem critical/warning: a severidade da spec vai no nome da política.
- **E7 — forwarder de logs próprio** (Node 20, fora da VPC) em vez do `newrelic-log-ingestion` do SAR (cria IAM role, bloqueado no Academy). Liga sozinho quando existe `/oficina/<env>/newrelic_license_key`. `service.name` mantido `oficina-mecanica` (nome atual), não `oficina-app` da spec.

## Lições operacionais

- Ler logs de CI com `gh run view <id> --log-failed` em vez de pedir print.
- Windows não grava o bit de execução: `git update-index --chmod=+x` e chamar scripts via `bash scripts/x.sh` na pipeline.
- Script que imprime valor consumido por `$(...)` deve silenciar o stdout dos demais comandos (`head-bucket` vazou JSON).
- Tags de actions mudam de formato (`trivy-action` passou a `vX.Y.Z`): conferir com `gh api repos/<org>/<repo>/tags`.
- Heredoc grande no shell da sessão quebra com aspas: usar a ferramenta de escrita para arquivos longos; `perl -0` em arquivo CRLF só com `?` nos padrões e `-i`.
- `aws` CLI sem região no perfil local: usar `--region us-east-1` ou `$env:AWS_DEFAULT_REGION`.
- Conferir pós-destroy com os serviços (`describe-instances`, `describe-volumes`, `describe-addresses`): a API de tags demora a esquecer recursos.
- Valores de secrets e credenciais são sempre do usuário; nunca ecoar no chat (credenciais coladas devem ser tratadas como expostas).

- **PR empilhado + squash perde alterações.** Na E5, o #2 era empilhado sobre o #1; depois do squash do #1, a reconciliação manual descartou o `ci.yml` e o README do #2 (o `terraform-check` que passou
  em `homolog` só rodava `fmt`). Regra: o usuário mergeia o PR da base e **avisa o Claude**, que religa a base para `homolog` com **rebase**; nunca `git pull` na branch do PR empilhado.
  Depois de qualquer merge empilhado, conferir `git show --stat` do commit mergeado contra o que foi escrito.
- Comandos destrutivos de Docker (`compose down -v`, `volume rm`) são barrados para o Claude; usar projeto Compose separado (`-p`) e deixar a limpeza de volumes para o usuário.
- `jq` não existe no Git Bash local (existe nos runners): validar scripts com um shim em Node fora do repositório. `java -cp` no Windows separa com `;`. Snippets Java curtos: `printf` + arquivo.
- Num HTTP API, ausência de `Authorization` = 401 e `isAuthorized=false` = 403; filtros JAX-RS rodam depois da checagem de `@RolesAllowed` no RESTEasy Reactive: **testar com requisição sem token**, não só com token.
- Workflows de infra que só aplicam em `push` não têm como subir um ambiente destruído sem re-executar um run antigo: dar a todo pipeline de infra um `workflow_dispatch` de apply.

## Como saber se a AWS "deu certo" (sessão consolidada)

- **`plan` em PR** (infra-k8s, infra-database): testa a sessão com `sts get-caller-identity`. Sessão ativa = roda de verdade e **comenta o plano no PR**; erro de Terraform = job
  vermelho. Sessão expirada = job **verde mas pulado** (só um `notice` "Sem sessão AWS ativa..." nas anotações e **nenhum comentário de plano**). Verde sem comentário **não** valida nada:
  renovar credenciais (`set-aws-session-secrets.sh`) **antes** de abrir PR na sessão AWS e conferir que o comentário apareceu.
- **`apply`** (push em `homolog`/`main` ou *Run workflow*): **não** ignora sessão expirada; `configure-aws-credentials` falha com token inválido (vermelho). Sucesso = resumo do run com
  URL/ECR (k8s) ou endpoint e data (database, após `db-instance-available`). Falha = `gh run view <id> --log-failed`.
- **Confirmação real:** itens ⬜ do checklist (curl com/sem `X-Gateway-Key`, smoke tests, Postman) e, no fim, `describe-instances`/`describe-volumes`/`describe-addresses` pós-destroy.
- O `deploy.yml` do auth-lambda tem o job `stack` (SSM `/oficina/<env>/*`): sem infra de pé, o push ignora o apply com aviso; o disparo manual falha de propósito.

## Histórico de sessões

- **2026-09-14 (sessão 1)** — E0 completa: `gh`/Python/`git-filter-repo` instalados, org criada,
  split dos 4 repos, pipelines, rulesets, environments, PRs infra-k8s#1 e monorepo#8.
- **2026-09-15 (sessão 2)** — Workspace movido para `Projetos\FIAP`; `CLAUDE.md` do workspace e
  `PROGRESSO.md` reescritos com os novos caminhos.
- **2026-10-06 (sessão 3)** — E1 completa: migrations V5/V6, ClientStatus value object, conversão 
  de TIMESTAMP→TIMESTAMPTZ em todos os domínios/entidades/DTOs, índices de performance e 
  segurança, pool de conexões, testes (ClientStatusTest, ClientPanacheRepositoryTest), 
  documentação ER. mvn verify: 264/265 testes passam (1 erro de runtime não-crítico).
- **2026-10-07 (sessão 4)** — Correção de `WorkOrderRepositoryAdapterTest.findActive_...`: a causa era a
  suíte não subir (H2 × migrations Postgres). Migrado `test` para Postgres/Dev Services e corrigidos bugs
  reais expostos: V5 (`app_users.updated_at` inexistente; CHECK de status com `IN_PROGRESS/APPROVED` em vez
  de `IN_EXECUTION`), V6 (nome do banco fixo), placeholder Flyway `lambda-ro-password` × `${lambda_ro_password}`,
  `order_number` NOT NULL, cast `LocalDateTime`→`OffsetDateTime` em `averageExecutionTimeMinutes`.
  mvn test: 265/265. Docker 29 + Testcontainers 1.20.1 exige `api.version=1.44` em `~/.docker-java.properties`.
- **2026-10-07 (sessão 4, continuação)** — `homolog` alinhada com `main` nos 2 repos defasados
  (oficina-app#3, oficina-infra-k8s#4; auth-lambda e infra-database já iguais); `CLAUDE.md` do workspace
  agora manda feature → `homolog` → `main`. E2: spec lida e plano fechado (ver "Próximo passo"); nenhum
  Terraform escrito. Sessão encerrada porque as credenciais AWS Academy invalidaram.
- **2026-10-16 (sessão 5)** — Credenciais renovadas e secrets publicados. Bucket de state antigo inexistente
  (nome S3 é global; conta Academy reciclada) → convenção `oficina-tfstate-<account-id>` via `-backend-config`.
  E2 implementada em `oficina-infra-k8s` (PR #6): locals por workspace, SG com faixas do API Gateway
  (+precondition de 60 regras), RDS removido, `ssm_outputs.tf`, ECR com scan, pipeline completa
  (validate/plan comentado/apply por branch/destroy com confirmação), README. Trivy: AWS-0104/0164/0031
  aceitos em `.trivyignore` (sem NAT; app usa `:latest`). `observability.tf` adiado para E7.
  Nenhum `plan`/`apply` real ainda.
- **2026-10-16 (sessão 5, fechamento)** — PR infra-k8s#6 mergeado em `homolog`; `apply` em `hml` com sucesso
  (39 recursos, 6 parâmetros SSM). Correções feitas durante o CI: tag `trivy-action` (`0.28.0` → `v0.36.0`),
  bit de execução dos scripts (`git update-index --chmod=+x`; Windows não grava) e `bootstrap-state-bucket.sh`
  vazando JSON do `head-bucket` em stdout. Lições: ler logs de CI via `gh run view --log-failed`; todo script
  que imprime valor consumido por `$(...)` deve silenciar o stdout dos demais comandos. Validações do usuário
  após o apply: "parece tudo ok" (sem evidência detalhada registrada). **Ainda não validado**: `apigateway:*` e
  trust policy da `LabRole` para Lambda (o apply da E2 não exercita nenhum dos dois). `prod` ainda não aplicada.
- **2026-10-16 (sessão 5, E3)** — E3 implementada em `oficina-infra-database` (PR #1): RDS PG16 por workspace
  (hml sem backup, prod 1 dia), parameter group (consulta lenta 500 ms, log de conexões, `rds.force_ssl=1`), SG
  do RDS, rede lida do SSM, publica `db_endpoint/db_port/db_name/rds_security_group_id`; pipeline igual à da E2 com
  `aws rds wait db-instance-available` e aviso de custo. **Desvio da spec 07 §2**: o SG do RDS não referencia o SG do
  Lambda (só existe na E5, aplicado depois) — a E5 deve ler `/oficina/<env>/rds_security_group_id` e criar a própria
  `aws_vpc_security_group_ingress_rule` 5432. **Decisão do plan**: usa senha placeholder (a real é secret de
  environment; usá-lo no plan pediria aprovação de `producao` a cada PR) — o plan mostra `password` como alterada.
  Senha com `/ @ "` ou espaço é recusada na validação (limites do RDS). Mudança coordenada de TLS: oficina-app#8
  (JDBC prod) já aberto; **o Lambda (E5) também precisa de `sslmode=require`**.
- **2026-10-16 (sessão 5, fechamento da E3)** — PRs mergeados: infra-database#1, app#8 (`sslmode=require` em prod), app#9. O primeiro
  `apply` de hml falhou de propósito na trava do `DB_PASSWORD` (secret de **environment** `homologacao`, não existia; nada foi criado);
  após o secret, re-run OK. `aws` CLI exigiu `--region`/`AWS_DEFAULT_REGION` (config local sem região). Secrets que existem:
  org `ALLOWED_CIDR` + `AWS_*`; env `homologacao` de infra-database `DB_PASSWORD`. **Ainda sem**: `producao` (DB_PASSWORD), e os do app
  (gateway key, JWT, mailer, seed) — entram com E4/E5/E6.
- **2026-10-16 (sessão 5, E4)** — PR 1/2 (oficina-app#11): `MeResource` (`/me`, só CLIENT), `AuthenticatedCaller`+`OwnershipGuard`
  (404, não 403, para recurso alheio), `ClientSelfService` (reaproveita `CreateWorkOrderUseCase`/`ChangeWorkOrderStatusUseCase`; o cliente só informa
  `vehicleId`+`notes`, nunca serviços/peças), `GatewayKeyFilter`, matriz RBAC rota×papel com JWT RS256 real (validada por mutação), gate JaCoCo ampliado.
  PR 2/2 (#12): `CorrelationIdFilter` como filtro do **Vert.x** (`Filters`), não JAX-RS — só assim o 401/403 gerado pela segurança também leva
  `X-Correlation-Id`; id recebido é validado (anti log injection); `BusinessMetricsPort` + adapter Micrometer (aplicação sem Micrometer); 6 métricas do spec;
  `oficina_auth_denied_total` via eventos de segurança do Quarkus (anônimo = `unauthenticated`, papel errado = `forbidden`); kustomize `base`+`overlays/{hml,prod}`
  (seed só em hml; HPA hml 1→2, prod 2→4); NetworkPolicy 443; Postman com pasta do cliente (precisa de gateway + Lambda). Com o gateway ligado, requisição sem a chave
  recebe 403 só depois de autenticar (sem token → 401: ver "Decisões", correção da sessão 7). **Não validado**: `kubectl apply` em cluster e o Postman contra gateway real. **Ficou para a E7**:
  `OTEL_*`/`NEW_RELIC_*` no ConfigMap. **Secrets do app ainda inexistentes** (entram no deploy): `APP_GATEWAY_KEY`, `LAMBDA_RO_PASSWORD`, chaves JWT, SMTP, senhas de seed.
- **2026-10-19 (sessão 6, E5)** — Credenciais do usuário colocadas em `~/.aws` (nunca repetir valores no chat). E4 fechada: app#11, #12, #13 mergeados.
  **E5** em dois PRs no `oficina-auth-lambda` (#1 código, #2 Terraform/deploy), ambos mergeados: Java 21 sem framework + SnapStart (decisão do usuário), `Cpf` como
  Value Object, `AuthenticateByCpf` com portas, 401 idêntico, consulta antes de recusar, papel somente-leitura, log sem CPF/token/mensagem do driver, pool JDBC sob demanda
  (hook SnapStart aquece a criptografia e fecha o pool), Maven multi-módulo (`authorizer-function` entra na E6), Dockerfile só para build reprodutível. Terraform:
  função `java21`/`arm64` com alias `live`, SG sem saída para a internet (só 5432), regra de entrada no SG do RDS criada aqui via `rds_security_group_id` (desvio da E3 já
  previsto), log group 7 dias, `LabRole`. `deploy.yml` com `package`/`plan`/`apply`/`destroy` e `scripts/smoke-test.sh`. Contrato com a app validado fora do repositório
  (teste descartável). **Descobertas**: (1) falta o job de deploy da app (ver "Próximo passo"); (2) `terraform validate` passa sem o JAR; (3) o `plan` da CI roda de verdade
  (credenciais válidas) e falha só por SSM ausente; (4) PR empilhado só dispara CI depois de religar a base para `homolog`; (5) em `homolog` o `apply` falha no guard dos secrets
  sem criar nada (esperado). **Lições**: shell da sessão quebra com heredoc grande (usar a ferramenta de escrita); `printf` + arquivo para snippets Java.
- **2026-10-28 (sessão 7)** — **Deploy da aplicação** (oficina-app#15, mergeado): `deploy.yml` (imagem no ECR com tag = SHA e Trivy antes do push → túnel SSM → secrets do GitHub Environment →
  `kubectl apply -k` → rollout com rollback → `scripts/smoke-test.sh` → health pelo Ingress), stack sob demanda (push sem infra/sessão expirada = aviso verde; disparo manual = erro), composite
  action filtrada por `Environment`, `secret.yaml` fora do kustomize, chave pública JWT derivada. **Achados ao testar:** `docker-compose` quebrado desde a E1 (`LAMBDA_RO_PASSWORD`); smoke test
  rodado contra a app real no compose (11 verificações, negativos reprovam) confirmou o papel `oficina_auth_ro` no banco; **correção do E4** (filtro do gateway: anônimo → 401). Após o merge, o
  deploy passou verde ignorando por sessão AWS expirada. **E6** em dois PRs no `oficina-auth-lambda` (#3 authorizer, #4 gateway; **abertos**): authorizer Java 21 + SnapStart fora da VPC, sem
  banco/rede/segredo (78 testes, ataques clássicos de JWT, contrato com a `auth-function` validado com os JARs reais); Terraform do HTTP API (7 rotas, authorizer REQUEST, throttling 10/20 em
  `/auth/token`, CORS, access log sem `Authorization`, X-Gateway-Key sobrescrito, URL no SSM); `deploy.yml` com `stack`/`plan`/`apply` + `scripts/smoke-gateway.sh` (400/401, negado no gateway pelo access
  log, 429, fluxo completo do cliente). **Restaurados** `ci.yml` (`terraform-check` completo) e README, perdidos na reconciliação do #2 da E5. **Gap registrado:** infra-k8s e infra-database só aplicam
  em push (sem apply manual).
- **2026-10-28 (sessão 7, fechamento)** — #3 e #4 mergeados; #4 religado com `rebase --onto` (árvore idêntica, `--force-with-lease` só na branch do PR). O `terraform-check` completo rodou pela primeira vez no
  runner e pegou um estilo de `shellcheck` (SC2015: `A && B || C`) que o shellcheck local não pegou: corrigido com `if` explícito. Lição: o shellcheck do runner é mais estrito que o do Docker local — evitar `A && B || C`.
  Estado final: 4 repositórios em `homolog`, nenhum PR de código aberto, nada na AWS. **Próxima sessão começa pelo apply manual de infra-k8s e infra-database** (ver "Próximo passo").
- **2026-10-28 (sessão 8)** — `workflow_dispatch` de **apply** em `oficina-infra-k8s#7` e `oficina-infra-database#2` (mergeados): `confirm` vazio = apply, `destroy-<workspace>` = destroy; guard
  workspace × branch; `apply` com `!cancelled()` para o skip do `validate` não se propagar; actionlint limpo; READMEs atualizados. O CI dos PRs falhou no `plan` por secrets `AWS_*` expirados
  (a checagem só via "não vazio" e o `configure-aws-credentials` rejeitava o token): `plan` passou a testar `sts get-caller-identity` e a pular com aviso, como o auth-lambda. Ver "Como saber se a AWS deu certo".
  **Pronto para a sessão AWS** do lado do código; sem AWS resta escrever E7/E8/E9.
- **2026-10-28 (sessão 8, continuação)** — **E7 escrita sem AWS** em 4 PRs. `oficina-app#19`: `quarkus-opentelemetry` (desligado por padrão; liga com `NEW_RELIC_LICENSE_KEY` no Secret), amostragem por ambiente, testes de `traceId`/`spanId` no MDC e de vazamento de senha, anotação `newrelic.io/scrape`, `correlationId` aceita `=`; 431/431. `oficina-infra-k8s#8`: `nri-bundle` 8.0.39 via HelmChart do k3s com licença no SSM (chart renderizado com `helm template`; achado do `integrations_filter`). `oficina-infra-k8s#9`: `observability/` (provider `newrelic`: 2 dashboards, 6 políticas/8 condições, Synthetics) com workflow que ignora sem sessão/secrets. `oficina-auth-lambda#5`: forwarder de logs (Node, 7 testes) e `X-Correlation-Id` no gateway. Lição: heredoc grande com aspas quebrou o shell de novo (usar Write); `perl -0` em arquivo com `$` no replacement pediu cuidado.
- **2026-10-28 (sessão 8, fechamento)** — **E8 e E9 escritas sem AWS.** `oficina-app/docs/`: 17 ADRs (`adr/`) e 3 RFCs (`rfc/`) a partir das specs e do que foi implementado (ADR 0010/0011 descrevem os desvios: só os traces são OTLP; SSM passou a conter a licença New Relic), `arquitetura.md` (componentes com a fronteira da VPC, sequência de autenticação e de abertura/aprovação de OS), `erd.md` ligado à RFC 0002, `RUNBOOK.md` (substitui o `SESSION-GUIDE.md` da Fase 2), `orcamento.md` (medições a preencher) e `VALIDACAO-PROD.md`; `scripts/k3s-exec.sh` (kubectl por SSM Run Command). Seção Documentação nos READMEs dos outros 3 repos (auth-lambda#6, infra-k8s#10, infra-database#3). **Achados ao documentar a validação em produção:** (1) `prod` tem seed desligado, então smoke test da app e fluxo do cliente do `smoke-gateway.sh` ficam pulados (decisão D1); (2) o throttling do gateway (50 rps) impede gerar carga de HPA pela URL do gateway: usar o EIP; (3) merge em `main` dispara o deploy de prod, e o `apply` dos repos de infra não ignora sessão expirada: promover só durante a sessão; (4) squash diverge `homolog` e `main`: PR de sincronização. Correção no diagrama: transição inválida responde 422 (não 409).
