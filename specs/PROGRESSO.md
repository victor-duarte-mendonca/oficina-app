# Progresso da Fase 3

> Fonte única do estado do projeto. Ler no início da sessão; atualizar ao final.
> Última atualização: **2026-10-19** (sessão 6 — E4 e E5 concluídas no código; deploy da app e validação em AWS pendentes).

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
| — Deploy da aplicação (pipeline; spec 08 §6) | 08 | oficina-app | ⬜ lacuna: bloqueia validar E4/E5 |
| E6 API Gateway + authorizer | 03 | oficina-auth-lambda | ⬜ |
| E7 Observabilidade fim-a-fim (New Relic) | 05 | app + infra + lambda | ⬜ |
| E8 Documentação arquitetural (ADRs, diagramas, READMEs) | 09 | todos | ⬜ |
| E9 Runbook de sessão e custos | 10 | oficina-infra-k8s | ⬜ |

## Próximo passo

**E0–E5 concluídas no código** (E2/E3 também aplicadas e validadas em `hml`). Nada de pé na AWS. O que falta validar em AWS está
no checklist abaixo — e quase tudo depende de a **aplicação estar implantada em `hml`**, o que ainda não existe.

**Lacuna encontrada na sessão 6:** o `ci.yml` da `oficina-app` só tem `build-test` e `docker-build`. Não existe o job de **deploy** da
aplicação (spec 08 §6: ECR push → `kustomize` + `kubectl` via túnel SSM → rollout com rollback → smoke test). Ele não pertence a nenhuma
das etapas E0–E9 por nome, mas bloqueia validar E4 e E5 de ponta a ponta:
- o papel `oficina_auth_ro` **só nasce quando a aplicação migra o banco (V6)**; sem a app em `hml`, o smoke test da função E5 falha com 500;
- o smoke test do E6 e o critério "token do Lambda aceito pela app em execução" precisam da app respondendo.

**Ordem (as duas primeiras frentes não precisam de AWS):**
1. **Deploy da aplicação** em `oficina-app` (spec 08 §6): job `deploy` no `ci.yml` usando a composite action `.github/actions/k3s-kubeconfig`
   (já copiada na E0) + `kubectl apply -k k8s/overlays/<env>`, `rollout status` com rollback, e `smoke-test` (`/q/health`, `/q/metrics`).
   Branch `feature/deploy-app` a partir de `homolog`, PR para `homolog`.
2. **E6 — spec 03** em `oficina-auth-lambda`: API Gateway HTTP API, rotas, **authorizer** (módulo `authorizer-function`, fora da VPC),
   integração `HTTP_PROXY` → EIP do k3s, injeção de `X-Gateway-Key`, throttling por rota, logs. Branch `feature/e6-api-gateway`.
3. **Uma sessão AWS consolidada** (~4 h, ≈ US$ 0,3): subir `infra-k8s` → `infra-database` → deploy da app → `auth-lambda` → gateway, validar **todos** os
   itens ⬜ do checklist de uma vez e destruir na ordem inversa. Mais barato e mais confiável que uma sessão por etapa.
4. Depois: E7 (observabilidade, `OTEL_*`/`NEW_RELIC_*`), E8 (documentação), E9 (runbook). PRs `homolog` → `main` só quando quiser subir `prod`.

**Secrets a definir antes da sessão consolidada** (valores são do usuário; ver "Pendências"):
- Chave JWT: gerar **uma vez** (`oficina-app/generate-keys.sh`) e usar **a mesma** `privateKey.pem` na app e no Lambda.
- `oficina-auth-lambda` env `homologacao`: `LAMBDA_RO_PASSWORD`, `JWT_PRIVATE_KEY_PEM`.
- `oficina-app` env `homologacao`: `DB_USERNAME`, `DB_PASSWORD` (a mesma do `oficina-infra-database`), `LAMBDA_RO_PASSWORD` (a mesma do Lambda), `APP_GATEWAY_KEY`,
  chaves JWT, `MAILER_*`, `APP_SEED_*_PASSWORD`.

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

## Pendências

**Do usuário**
- [x] Mergear oficina-infra-k8s#1 (script de segredos)
- [x] Alinhar `homolog` com `main` (oficina-app#3, oficina-infra-k8s#4)
- [x] Reabrir o AWS Academy, rodar `set-aws-session-secrets.sh` e definir `ALLOWED_CIDR` (sessão 5)
- [x] Bucket de state: o nome fixo antigo não existia mais na conta; criado `oficina-tfstate-<account-id>` (a pipeline também o cria via `scripts/bootstrap-state-bucket.sh`)
- [x] `observability.tf` (nri-bundle) fica na E7 (adotado por padrão; reverter se quiser na E2)
- [ ] Mergear **oficina-app#12** (E4 2/2) e **oficina-app#13** (este registro), depois conferir o CI
- [ ] Definir `DB_PASSWORD` no environment `producao` de `oficina-infra-database` (só quando for subir `prod`)
- [ ] Adicionar `soat-architecture` na org `victor-duarte-mendonca` — pode ficar para o fim da fase
- [ ] Apagar a pasta local `Projetos\tech-challenge-fiap` (tudo já está no GitHub; nada local pendente)

**Validações de primeira sessão AWS** (spec 00 §6, spec 03 §8) — fazer junto com E2:
- [ ] `apigateway:*` liberado na conta Academy? (`terraform plan` mínimo)
- [ ] `LabRole` com trust policy para `lambda.amazonaws.com` e permissão de ENI na VPC?

## Estado atual dos repositórios (fim da sessão 6)

| Repo | Branch local | Conteúdo | Pipeline | Checks obrigatórios |
|---|---|---|---|---|
| `oficina-app` | `homolog`/`main` | app Quarkus com E1+E4 (CLIENT, `/me`, correlação, métricas), `k8s/` em kustomize (base + overlays hml/prod), Postman, `specs/` | `ci.yml` (**sem job de deploy ainda**) | `build-test`, `docker-build` (Trivy CRITICAL/fixed) |
| `oficina-infra-k8s` | `main` | E2 completa: VPC, EC2/k3s, ECR, SG (faixas do API Gateway), contrato SSM, workspaces `hml`/`prod`, pipeline com apply/destroy | `terraform.yml` | `validate`, `plan` |
| `oficina-infra-database` | `main` | E3 completa: RDS PG16, parameter group (force_ssl), SG, contrato SSM, workspaces, pipeline com apply/destroy | `terraform.yml` | `validate`, `plan` |
| `oficina-auth-lambda` | `homolog` | E5: `auth-function` (Java 21 + SnapStart), `terraform/` da função, `scripts/smoke-test.sh`; authorizer e API Gateway entram na E6 | `ci.yml` (`build-test`, `terraform-check`) + `deploy.yml` (package/plan/apply/destroy) | `build-test`, `terraform-check` |

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

## Lições operacionais

- Ler logs de CI com `gh run view <id> --log-failed` em vez de pedir print.
- Windows não grava o bit de execução: `git update-index --chmod=+x` e chamar scripts via `bash scripts/x.sh` na pipeline.
- Script que imprime valor consumido por `$(...)` deve silenciar o stdout dos demais comandos (`head-bucket` vazou JSON).
- Tags de actions mudam de formato (`trivy-action` passou a `vX.Y.Z`): conferir com `gh api repos/<org>/<repo>/tags`.
- Heredoc grande no shell da sessão quebra com aspas: usar a ferramenta de escrita para arquivos longos; `perl -0` em arquivo CRLF só com `?` nos padrões e `-i`.
- `aws` CLI sem região no perfil local: usar `--region us-east-1` ou `$env:AWS_DEFAULT_REGION`.
- Conferir pós-destroy com os serviços (`describe-instances`, `describe-volumes`, `describe-addresses`): a API de tags demora a esquecer recursos.
- Valores de secrets e credenciais são sempre do usuário; nunca ecoar no chat (credenciais coladas devem ser tratadas como expostas).

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
  recebe 403 antes da autenticação (inclusive sem token). **Não validado**: `kubectl apply` em cluster e o Postman contra gateway real. **Ficou para a E7**:
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
