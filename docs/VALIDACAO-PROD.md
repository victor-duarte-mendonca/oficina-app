# Validação em produção — roteiro da sessão AWS consolidada

> Tudo o que é preciso para validar, numa única sessão do AWS Academy, os itens ⬜ de E1 a E7 **em produção** (`main`, workspace `prod`,
> Environment `producao`). Todo o código está escrito e mergeado em `homolog`; **nada está de pé na AWS**. Operação geral e custo:
> [`RUNBOOK.md`](RUNBOOK.md). Estado: [`specs/PROGRESSO.md`](../specs/PROGRESSO.md).
>
> Por que produção e não `hml`: é a escolha do usuário para esta sessão. Consequência a saber: `prod` é `t3.medium` (cabe o `nri-bundle`
> completo e o HPA 2→4), mas **não há ensaio prévio** (ADR 0015). Se algo quebrar, o conserto passa por `homolog` e `main` de novo (§8).

## 1. Decisões do usuário (resolver antes da sessão)

| # | Decisão | Por que importa | Recomendação |
|---|---|---|---|
| D1 | **Ligar o seed de usuários em `prod`?** O overlay `prod` define `APP_SEED_ENABLED: "false"` | Sem seed não existe admin em `prod`: o smoke test da app pula o login e o `smoke-gateway.sh` **pula o fluxo completo do cliente** (precisa de um admin para criar o cliente). Também não há como logar na demonstração | Ligar só para a validação: PR em `oficina-app` mudando `k8s/overlays/prod/kustomization.yaml` para `"true"`, com `APP_SEED_ADMIN_PASSWORD` e `APP_SEED_MECHANIC_PASSWORD` fortes em `producao`. Aceitar que há um admin conhecido no ambiente de laboratório; reverter depois da gravação |
| D2 | **Conta New Relic agora?** | Sem ela a E7 fica sem validar (traces, métricas, logs, dashboards, alertas). Nada quebra sem ela | Criar a conta (free tier) **antes** da sessão e definir os secrets da §3 |
| D3 | Filtro do gateway na app: anônimo recebe 401, não 403 | Hoje 403 só vale para autenticado sem a chave. Mudar exige filtro no Vert.x | Aceitar 401 (já documentado no ADR 0007) |
| D4 | HTTP API não propaga W3C: o trace OTLP começa na aplicação | A spec 05 §10 pedia "trace único gateway → aplicação"; a correlação gateway↔app é por `X-Correlation-Id` | Aceitar e mostrar a correlação por log no vídeo |
| D5 | Acesso do `soat-architecture` aos 4 repositórios | Pendente do E0; depende de convite na org | Fazer no fim da fase |

## 2. Promoção `homolog` → `main` (o que a promoção dispara)

Merge em `main` **dispara o deploy de produção** em cada repositório, e o Environment `producao` pede a **aprovação** do usuário
(*Actions → run → Review deployments*). Portanto:

1. **Promova durante a sessão, uma de cada vez, na ordem de subida** (§4): `infra-k8s` → `infra-database` → `app` → `auth-lambda`. O merge
   faz o papel do *Run workflow*.
2. **Não promova antes da sessão.** O `apply` dos repositórios de infra **não** ignora sessão expirada: com credenciais vencidas fica
   vermelho (os de app e auth-lambda ignoram com aviso, e ficariam "verdes" sem ter aplicado).
3. PR `homolog` → `main` em cada repositório: checks obrigatórios (`build-test`, `docker-build`, `terraform-check`, `validate`, `plan`),
   squash, branch atualizada com a base. O Claude abre os PRs; **o usuário mergeia**.
4. **Squash diverge as histórias** (a `main` recebe um commit novo; a `homolog` não): o PR seguinte `homolog` → `main` pode listar commits
   já promovidos ou conflitar. Resolva com um PR de sincronização `main` → `homolog` (como `oficina-infra-k8s#4/#5`), nunca à mão na branch.
5. Alternativa sem merge: *Run workflow* com *Use workflow from: `main`* (a `main` precisa já ter o código).

## 3. Pré-requisitos (sem AWS, antes da sessão)

### 3.1 Segredos por Environment `producao`

Valores são **sempre do usuário**; o Claude nunca os repete. "Igual" = o **mesmo valor** em repositórios diferentes.

| Repositório | Secret (`producao`) | Obrigatório | Observação |
|---|---|---|---|
| `oficina-infra-database` | `DB_PASSWORD` | sim | já definido em 2026-10-19; mín. 16 caracteres, sem `/ @` aspas ou espaço. `DB_USERNAME` opcional (padrão `oficina_admin`) |
| `oficina-app` | `DB_USERNAME` | sim | use `oficina_admin` |
| `oficina-app` | `DB_PASSWORD` | sim | **igual** ao de `oficina-infra-database` |
| `oficina-app` | `LAMBDA_RO_PASSWORD` | sim | senha do papel `oficina_auth_ro`; **igual** ao do `oficina-auth-lambda` |
| `oficina-app` | `JWT_PRIVATE_KEY_PEM` | sim | par gerado **uma vez**; **igual** ao do `oficina-auth-lambda` |
| `oficina-app` | `APP_GATEWAY_KEY` | sim em `prod` | **igual** ao do `oficina-auth-lambda` |
| `oficina-app` | `APP_SEED_ADMIN_PASSWORD`, `APP_SEED_MECHANIC_PASSWORD` | só com D1 | senhas fortes |
| `oficina-app` | `MAILER_HOST`, `MAILER_USERNAME`, `MAILER_PASSWORD` | não | sem eles o e-mail falha e é contado (a transição é preservada) |
| `oficina-app` | `NEW_RELIC_LICENSE_KEY` | só com D2 | licença de **ingestão** (traces) |
| `oficina-auth-lambda` | `LAMBDA_RO_PASSWORD` | sim | **igual** ao da app |
| `oficina-auth-lambda` | `JWT_PRIVATE_KEY_PEM` | sim | **a mesma** chave privada da app |
| `oficina-auth-lambda` | `APP_GATEWAY_KEY` | sim | **o mesmo** valor da app |
| `oficina-auth-lambda` | `APP_SEED_ADMIN_PASSWORD` | recomendado (D1) | habilita o fluxo completo do cliente no smoke test |
| `oficina-infra-k8s` | `NEW_RELIC_LICENSE_KEY` | só com D2 | **a mesma** licença; precisa existir **antes** do `apply` (o k3s a lê no boot) |
| `oficina-infra-k8s` | `NEW_RELIC_API_KEY`, `NEW_RELIC_ACCOUNT_ID`, `ALERT_EMAIL` | só com D2 | User key `NRAK-...` (**não** é a licença), id da conta e e-mail dos alertas |

Já existem: organization secret `ALLOWED_CIDR` e `DB_PASSWORD` de `producao` em `oficina-infra-database`. `AWS_*` são organization secrets
repostos por sessão (§4, passo 0).

### 3.2 Chaves e valores a gerar

```bash
cd oficina-app && ./generate-keys.sh        # gera keys/privateKey.pem (PKCS#8) e keys/publicKey.pem
```

Cole o conteúdo de `keys/privateKey.pem` como `JWT_PRIVATE_KEY_PEM` nos **dois** repositórios (a chave pública é derivada pelo Terraform
do authorizer). Gere senhas e a `APP_GATEWAY_KEY` (ex.: `openssl rand -base64 24 | tr -d '/+='`). Não cole valores no chat: se algum for
colado, trate-o como exposto e troque.

### 3.3 `ALLOWED_CIDR`

O IP de casa muda. Antes da sessão: `curl -s https://checkip.amazonaws.com` e confira o organization secret `ALLOWED_CIDR` (`x.x.x.x/32`).
Ele libera SSH e a porta 80 do cluster para o seu IP (carga de HPA e `curl` direto ao EIP).

### 3.4 Ferramentas locais

`aws` CLI v2, `gh` (`C:\Program Files\GitHub CLI\gh.exe`), `curl`, `hey` (carga do HPA), Postman. O acesso ao cluster é por
[`scripts/k3s-exec.sh`](../scripts/k3s-exec.sh) (SSM Run Command, sem SSH e sem expor a 6443).

### 3.5 Conta New Relic (D2)

Criar a conta (free tier), copiar o **account id**, a **license key de ingestão** (*API keys → INGEST - LICENSE*) e criar uma **User API key**
(`NRAK-...`). Definir os secrets da §3.1 e um alerta de consumo em 70 GB/mês na própria conta.

## 4. Roteiro da sessão (ordem obrigatória)

Marque cada passo; a coluna "Esperado" é o critério. Falha em qualquer passo: `gh run view <id> --log-failed` e a §7.

| # | Passo | Esperado |
|---|---|---|
| 0 | Start Lab; exportar `AWS_*`; `oficina-infra-k8s/scripts/set-aws-session-secrets.sh`; `aws sts get-caller-identity`; anotar saldo em [`orcamento.md`](orcamento.md) | conta e região `us-east-1`; secrets publicados |
| 1 | **`oficina-infra-k8s`**: promover (merge em `main`) ou *Run workflow* → **Terraform**, `workspace=prod`, `confirm` vazio; aprovar o Environment `producao` | job `apply` verde; resumo com URL e ECR; `k3s-exec.sh prod "kubectl get nodes"` = `Ready` (~5 min) |
| 2 | **`oficina-infra-database`**: idem, **Terraform** `prod` | RDS `available`; `/oficina/prod/db_endpoint` existe (~8 min) |
| 3 | **`oficina-app`**: promover ou *Run workflow* → **Deploy** em `main`; aprovar | imagem no ECR com tag = SHA; rollout concluído; smoke test verde; health pelo Ingress (~6 min) |
| 4 | **`oficina-auth-lambda`**: promover ou *Run workflow* → **Deploy** (`confirm` vazio); aprovar | smoke da função e **smoke pelo gateway** verdes; `/oficina/prod/api_gateway_url` publicado (~3 min) |
| 5 | **`oficina-infra-k8s`** → **Observability** (`workspace=prod`), só com D2 | dashboards e políticas no New Relic (o job ignora sem secrets) |
| 6 | Conferências da §5 | todas ✅ |
| 7 | Demonstração de escala ([RUNBOOK §5](RUNBOOK.md)) | scale-out sem pod `Pending`; scale-in depois de ~3 min |
| 8 | **Encerramento** ([RUNBOOK §6](RUNBOOK.md)): destroy na ordem inversa, varredura de órfãos vazia, saldo final em `orcamento.md`, End Lab | nada retornado pela varredura |

## 5. Checklist de validação por etapa (os ⬜ do PROGRESSO)

`$API` = `aws ssm get-parameter --name /oficina/prod/api_gateway_url --query Parameter.Value --output text`.
`$EIP` = `aws ssm get-parameter --name /oficina/prod/k3s_eip --query Parameter.Value --output text`.
`kx` = `scripts/k3s-exec.sh prod`.

### E0 — repositórios
- [ ] `soat-architecture` com acesso aos 4 repositórios (D5).

### E1 — banco
- [ ] Migrations V1–V6 num **RDS real** com TLS: `kx "kubectl -n oficina logs deploy/oficina-app | grep -i 'migrat'"` mostra as migrações aplicadas e o pod fica `Ready`.

### E2 — infra K8s
- [ ] `apply` em `prod` (passo 1) · [ ] nó `Ready` · [ ] 6 parâmetros SSM: `aws ssm get-parameters-by-path --path /oficina/prod --query 'Parameters[].Name'`.
- [ ] **`apigateway:*` liberado** e política do log group aceita (o apply do passo 4 prova; se falhar, plano B na spec 03 §8).
- [ ] **`LabRole` aceita `lambda.amazonaws.com` e tem permissão de ENI** (idem; `aws iam get-role --role-name LabRole`).

### E3 — banco
- [ ] `apply` em `prod` (passo 2) · [ ] 4 parâmetros SSM de banco.
- [ ] **TLS obrigatório**: de um pod, `sslmode=require` conecta e `sslmode=disable` é **recusado** (a senha e o host saem do Secret **dentro do node**, para não ficarem no histórico do SSM Run Command):
  ```bash
  scripts/k3s-exec.sh prod 'P=$(kubectl -n oficina get secret oficina-secrets -o jsonpath={.data.DB_PASSWORD} | base64 -d); H=$(kubectl -n oficina get secret oficina-secrets -o jsonpath={.data.DB_HOST} | base64 -d); kubectl -n oficina run psqltest --rm -i --restart=Never --image=postgres:16-alpine --env=PGPASSWORD=$P -- psql "host=$H user=oficina_admin dbname=oficina_db sslmode=disable" -c "select 1"'
  ```
  O esperado é falha com `no pg_hba.conf entry ... no encryption`; repita com `sslmode=require` e deve responder `1`.

### E4 — aplicação
- [ ] `kubectl apply` no cluster (passo 3) · [ ] `X-Correlation-Id` e `mdc` no **log JSON do pod**: `kx "kubectl -n oficina logs deploy/oficina-app --tail=20"` traz JSON com `mdc.correlationId`, `service.name` e `env=prod`.
- [ ] `/q/metrics` no pod: já coberto pelo smoke test do passo 3 (port-forward). À mão: `kx 'curl -s $(kubectl -n oficina get pod -l app=oficina-app -o jsonpath={.items[0].status.podIP}):8080/q/metrics | grep oficina_'`.| grep oficina_"`.
- [ ] Coleção Postman (`postman/Oficina-Demo-Fase2.postman_collection.json`) com `gatewayUrl = $API`.

### E5 — função de autenticação
- [ ] `plan`/`apply` reais (passo 4) · [ ] **SnapStart** aceito e **cold start < 1 s**: invoque após ~10 min parada e leia `Restore Duration`:
  `aws logs filter-log-events --log-group-name /aws/lambda/oficina-prod-auth-token --filter-pattern '"Restore Duration"' --query 'events[].message'`.
- [ ] `reserved_concurrent_executions = 10` aceito (se recusar: `-1`, plano B nas specs).
- [ ] Smoke test do alias: 400 e 401 com o banco alcançado (faz parte do passo 4).
- [ ] **Nenhum CPF completo no CloudWatch**: `aws logs filter-log-events --log-group-name /aws/lambda/oficina-prod-auth-token --filter-pattern '"<cpf de teste sem máscara>"'` volta vazio.
- [ ] Token do Lambda aceito pela app **em execução**: `GET $API/me` com o token → 200.

### Deploy da aplicação
- [ ] Imagem no ECR com tag = SHA · [ ] `kubectl apply -k` pelo túnel SSM · [ ] rollout e **rollback automático** (opcional: forçar uma imagem inválida em `hml`, não em `prod`) · [ ] health pelo Ingress de dentro da EC2 (feito pela pipeline).

### E6 — gateway e authorizer
- [ ] **401 sem header, 403 com token recusado**: `curl -i $API/me` (sem `Authorization`) → 401; com token adulterado → 403.
- [ ] Access log do gateway com `integrationLatency` vazio nas negadas: `aws logs filter-log-events --log-group-name /aws/apigateway/oficina-prod --limit 20`.
- [ ] **Throttling → 429** em `POST /auth/token` (o `smoke-gateway.sh` do passo 4 cobre; ou 40 chamadas em sequência).
- [ ] **Fluxo completo do cliente** pelo gateway (admin cria cliente → CPF → token → `/me` → abre OS): só com D1.
- [ ] `curl -i http://$EIP/me/work-orders -H "Authorization: Bearer $TOKEN"` do **seu IP**, sem `X-Gateway-Key` → **403**.
- [ ] `X-Correlation-Id` do gateway chega à app: a resposta de `$API/me` traz `X-Correlation-Id` igual ao `requestId` do access log.
- [ ] Coleção Postman contra o gateway.

### E7 — observabilidade (só com D2)
- [ ] EC2 leu a licença do SSM e os agentes subiram: `kx "kubectl -n newrelic get pods"`; se não, `kx "grep -i 'AVISO' /var/log/cloud-init-output.log"` (o **LabInstanceProfile precisa de `ssm:GetParameter`**; o k3s sobe sem agentes se falhar).
- [ ] **`kubectl top nodes` e `kubectl top pods -A`** com folga para o HPA chegar a 4 réplicas.
- [ ] Prometheus-agent coleta `/q/metrics` (anotação `newrelic.io/scrape`): `FROM Metric SELECT count(*) WHERE metricName LIKE 'oficina_%'`.
- [ ] Traces OTLP chegam: `FROM Span SELECT count(*) WHERE entity.name = 'oficina-mecanica'`.
- [ ] **Confirmar as NRQL** contra dados reais (nomes de span, `_sum`/`_count` do timer, `K8sContainerSample.reason`) e corrigir `dashboards/*.json.tftpl` e `alerts.tf`.
- [ ] Os 2 dashboards carregam do JSON versionado e mostram dados · [ ] as 6 políticas existem e **2 disparam de propósito** (derrubar um pod: `kx "kubectl -n oficina delete pod -l app=oficina-app"`; forçar uma transição inválida de OS) e o e-mail chega.
- [ ] Monitor Synthetics em `/health` e alerta de disponibilidade.
- [ ] Logs do gateway e dos Lambdas no New Relic com `correlationId` (forwarder; **LabRole aceita a assinatura**): `FROM Log SELECT * WHERE correlationId IS NOT NULL`.
- [ ] `grep` nos logs: nenhum CPF completo nem token.

## 6. Como saber se deu certo

- **`plan` em PR** (infra): com sessão ativa roda de verdade e **comenta o plano no PR**; com sessão expirada fica **verde mas pulado** (só um
  `notice`, sem comentário). Verde sem comentário **não valida nada**.
- **`apply`**: não ignora sessão expirada (vermelho com `security token ... invalid`). Sucesso = resumo do run. Deploys de app e auth-lambda
  **ignoram com aviso** se a infra não existe (no push) e **falham** no disparo manual.
- A confirmação real são as conferências da §5 e a varredura de órfãos.

## 7. Se algo falhar (riscos mapeados)

| Risco | Sintoma | Plano B |
|---|---|---|
| Conta bloqueia `apigateway:*` | apply do auth-lambda falha em `aws_apigatewayv2_*` | spec 03 §8 (Traefik como gateway + Lambda Function URL) |
| `LabRole` sem trust para Lambda ou sem ENI | `aws_lambda_function` falha (VPC) | spec 02 §8: Lambda fora da VPC consultando a API da app |
| SnapStart indisponível | erro em `snap_start` ou cold start alto | spec 02 §3: runtime Node |
| `reserved_concurrent_executions` recusado | erro de limite de conta | usar `-1` |
| LabInstanceProfile sem SSM | `kubectl -n newrelic` vazio, AVISO no cloud-init | criar o Secret/HelmChart à mão pelo `k3s-exec.sh` ou seguir sem agentes |
| Pod `Pending` no HPA | memória do node | `maxReplicas: 3` ou `requests.memory: 320Mi` (ou `t3.large`, +US$0,17/sessão) |
| NRQL sem dados | widget vazio | ajustar a consulta ao nome real da métrica (§5, E7) |

## 8. Depois da sessão

1. **Destruir tudo** e conferir órfãos ([RUNBOOK §6](RUNBOOK.md)); anotar o saldo em `orcamento.md`.
2. Marcar no `specs/PROGRESSO.md` cada ⬜ que virou ✅ e registrar achados novos.
3. Correções encontradas em `prod` seguem o fluxo: `feature/x` a partir de `homolog` → PR para `homolog` → PR `homolog` → `main` (nunca
   feature direto para `main`). O merge em `main` redispara o deploy de produção, então só com sessão ativa.
4. Se a decisão D1 foi "ligar o seed", reverter `APP_SEED_ENABLED` para `"false"` depois da gravação do vídeo.
5. Entregas do usuário fora deste plano: documento PDF e vídeo de até 15 min.
