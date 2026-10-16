# Progresso da Fase 3

> Fonte única do estado do projeto. Ler no início da sessão; atualizar ao final.
> Última atualização: **2026-10-07** (sessão 4 — E1 corrigida, homolog alinhada, E2 preparada; pausa por credenciais AWS expiradas).

Workspace local: `C:\Users\victo\Desktop\Projetos\FIAP\` (os 4 repos + `oficina-front/` fora do escopo).

## Etapas (spec 00 §4)

| Etapa | Spec | Repo | Status |
|---|---|---|---|
| E0 Split dos repositórios + proteção + pipelines | 08 | todos | ✅ concluída |
| E1 Modelagem do banco (V5, `clients.status`, pool) | 01 | oficina-app | ✅ concluída |
| E2 Infra K8s (Terraform, workspaces hml/prod, SSM) | 06 | oficina-infra-k8s | ⬜ |
| E3 Infra DB (Terraform, módulo completo) | 07 | oficina-infra-database | ⬜ |
| E4 App: role CLIENT, ownership, correlação, métricas | 04 | oficina-app | ⬜ |
| E5 Lambda de autenticação por CPF | 02 | oficina-auth-lambda | ⬜ |
| E6 API Gateway + authorizer | 03 | oficina-auth-lambda | ⬜ |
| E7 Observabilidade fim-a-fim (New Relic) | 05 | app + infra + lambda | ⬜ |
| E8 Documentação arquitetural (ADRs, diagramas, READMEs) | 09 | todos | ⬜ |
| E9 Runbook de sessão e custos | 10 | oficina-infra-k8s | ⬜ |

## Próximo passo

**E2 — spec 06** em `oficina-infra-k8s`. **Não iniciada no código**: só a branch local `feature/e2-infra-k8s` (a partir de `origin/homolog`, vazia) existe. PR da E2 vai para `homolog`.
Bloqueia E4 (precisa da infra K8s pronta). O `apply` exige credenciais da sessão Academy.

**Ao retomar (ordem):**
1. Usuário: reabrir o AWS Academy (credenciais anteriores invalidaram) e rodar `scripts/set-aws-session-secrets.sh`; definir `ALLOWED_CIDR`.
2. Claude: ler `specs/06-infra-k8s-terraform.md` e implementar o escopo abaixo.
3. Claude: `terraform fmt`/`validate` local (v1.15.8 instalado; sem `tflint`/`trivy` — ficam para o CI), commit, PR para `homolog`.

**Plano de implementação já decidido (sessão 4)** — `oficina-infra-k8s`:
- Remover `db_password`/`db_instance_class` e o SG `rds` (RDS vive em infra-database; o SG de ingresso 5432 será criado lá a partir do SSM `k3s_security_group_id`).
- Workspaces `hml`/`prod` via `locals` (instance_type t3.small/t3.medium, volume 20 GiB); prefixo de nomes `oficina-<workspace>` (key pair e SG colidiriam entre stacks); `default_tags.Environment = workspace`.
- `backend.tf`: `workspace_key_prefix = "oficina/infra-k8s"`, `key = "terraform.tfstate"` (S3 grava em `<prefix>/<workspace>/terraform.tfstate`; spec pede `<workspace>.tfstate` — desvio mínimo a registrar).
- `security_groups.tf`: porta 80 também para as faixas do API Gateway (`data "aws_ip_ranges"`, `aws_vpc_security_group_ingress_rule` com `for_each`); atenção ao limite de 60 regras por SG.
- `ssm_outputs.tf`: 6 parâmetros `/oficina/<env>/{vpc_id,private_subnet_ids,k3s_security_group_id,k3s_eip,ecr_repository_url,application_url}`.
- `observability.tf` (nri-bundle): **adiar para E7** — o helm provider precisa do kubeconfig de um cluster que ainda não existe no `plan`. Confirmar com o usuário.
- Pipeline `terraform.yml`: `validate` (+tflint, trivy config), `plan` com comentário no PR, `apply` por branch (homolog→hml, main→prod, environments `homologacao`/`producao`), `destroy` via `workflow_dispatch` com confirmação digitada + varredura de órfãos por tag.
- README: diagrama Mermaid, pré-requisitos, comandos, tabela de custo.

**Fluxo de PRs (spec 08 §2):** `feature/x` → PR para `homolog` → valida → PR `homolog` → `main`. Nunca feature direto para `main`.

## Pendências

**Do usuário**
- [x] Mergear oficina-infra-k8s#1 (script de segredos)
- [x] Alinhar `homolog` com `main` (oficina-app#3, oficina-infra-k8s#4)
- [ ] **Reabrir o AWS Academy** (credenciais da sessão 4 invalidaram) e rodar `oficina-infra-k8s/scripts/set-aws-session-secrets.sh` — necessário para `plan`/`apply` da E2
- [ ] Definir o secret `ALLOWED_CIDR` (seu IP público /32; org-level, spec 08 §5) antes do primeiro `plan`
- [ ] Confirmar que o bucket `victor-duarte-mendonca-oficina-tfstate` existe na conta Academy atual (senão recriar; bootstrap manual fora do módulo)
- [ ] Decidir: `observability.tf` fica na E7 (recomendado) ou entra na E2
- [ ] Adicionar `soat-architecture` na org `victor-duarte-mendonca` — pode ficar para o fim da fase
- [ ] Apagar a pasta local `Projetos\tech-challenge-fiap` (tudo já está no GitHub; nada local pendente)

**Validações de primeira sessão AWS** (spec 00 §6, spec 03 §8) — fazer junto com E2:
- [ ] `apigateway:*` liberado na conta Academy? (`terraform plan` mínimo)
- [ ] `LabRole` com trust policy para `lambda.amazonaws.com` e permissão de ENI na VPC?

## Estado atual dos repositórios (após E0)

| Repo | Branch local | Conteúdo | Pipeline | Checks obrigatórios |
|---|---|---|---|---|
| `oficina-app` | `main` | app Quarkus completa (raiz = antigo `oficina/`), `.github/actions/k3s-kubeconfig`, `specs/` | `ci.yml` | `build-test`, `docker-build` (Trivy CRITICAL/fixed) |
| `oficina-infra-k8s` | `feature/script-segredos-sessao` (PR#1) | antigo `oficina/infra/` sem `rds.tf`/`rds_endpoint`; `.gitignore` | `terraform.yml` | `validate`, `plan` (plan só roda com secrets AWS presentes) |
| `oficina-infra-database` | `main` | só `rds.tf` + README — módulo incompleto até E3 | `terraform.yml` (validate = só `fmt`) | `validate`, `plan` |
| `oficina-auth-lambda` | `main` | README + `.gitignore` | `ci.yml` placeholder | `build-test`, `terraform-check` |

Histórico das Fases 1-2 preservado nos 3 repos derivados; `kubeconfig-raw` e `patch.json` removidos
do histórico. `test-keys/*.pem` em `oficina-app` são fixtures documentadas (não são segredo).

**Proteção (nos 4)**: ruleset `protecao-main-homolog` em `main` e `homolog` — sem push direto,
force-push ou delete; PR obrigatório; squash; checks obrigatórios com branch atualizada; **sem bypass**.
Repo: só squash merge, apaga branch após merge. Push direto testado e recusado (`GH013`).

**Environments (nos 4)**: `homologacao` (só branch `homolog`) e `producao` (só `main`, *required
reviewer* = Victor, self-review permitido). **Nenhum secret definido ainda** (org ou environment).

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
