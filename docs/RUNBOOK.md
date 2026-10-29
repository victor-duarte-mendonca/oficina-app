# Runbook — sessão AWS e controle de custo (Fase 3)

> Não é requisito do PDF: é o que impede que o requisito seja atendido e o orçamento acabe no meio do curso. Com quatro repositórios e
> uma conta que expira a cada 4 horas, subida e encerramento precisam ser mecânicos.
> Este runbook **substitui** o [`SESSION-GUIDE.md`](SESSION-GUIDE.md) (Fase 2, monorepo e pipeline única).
> Roteiro da **primeira** sessão (validação em produção): [`VALIDACAO-PROD.md`](VALIDACAO-PROD.md). Orçamento: [`orcamento.md`](orcamento.md).

## 1. Orçamento e ordem de perigo

- Crédito: **~US$48**, único para o curso, sem recarga. Esgotou, a conta é desativada.
- Sessão de 4 h com a stack de produção inteira: **≈ US$0,31**. Homologação, quando sobe, acrescenta ≈ US$0,18 (só para ensaiar mudança de
  infraestrutura ou migration antes de produção, [ADR 0015](adr/0015-homologacao-como-stack-separada.md)).
- Margem de ~90 sessões completas. **O risco não é a sessão; é o recurso esquecido.**

| Recurso | US$/h | US$/mês se esquecido | Para com "End Lab"? |
|---|---|---|---|
| RDS `db.t4g.micro` + 20 GiB | 0,019 | **13,70** | **não** |
| EC2 `t3.medium` | 0,0416 | 30,00 | sim (para; o volume continua) |
| EIP não associado | 0,005 | 3,60 | **não** |
| Volume EBS órfão (20 GiB gp3) | 0,0032 | 2,30 | **não** |
| API Gateway, Lambda, SSM, CloudWatch | por uso | ~0 | n/a |
| New Relic | — | 0 (free tier de 100 GB/mês) | n/a |

## 2. Início de sessão

```
[ ] Start Lab no AWS Academy; copiar as credenciais STS ("AWS Details" > "Show")
[ ] export AWS_ACCESS_KEY_ID=... AWS_SECRET_ACCESS_KEY=... AWS_SESSION_TOKEN=...
[ ] ./oficina-infra-k8s/scripts/set-aws-session-secrets.sh      (publica como organization secrets; vale até o fim da sessão)
[ ] aws sts get-caller-identity   → conta e região us-east-1 corretas
[ ] Anotar a hora de início (o timer de 4 h é rígido) e o saldo de crédito do painel (docs/orcamento.md)
```

Se a sessão expirar no meio, repita os dois primeiros passos. **Um `plan` verde sem comentário de plano não prova nada**: com credenciais
expiradas ele é pulado com um `notice` (ver [VALIDACAO-PROD.md](VALIDACAO-PROD.md) §6).

## 3. Subida (ordem obrigatória)

Sessão comum: **só produção** (`main`, workspace `prod`, Environment `producao` com aprovação). Para ensaio de infraestrutura, rode a mesma
sequência antes em `hml` (*Use workflow from* `homolog`), valide e só então promova (soma ~13 min e US$0,18).

Em todo workflow de apply manual: *Actions → workflow → Run workflow → **Use workflow from: `main`*** (ou `homolog` para `hml`), com
`confirm` **vazio** = apply (preenchido com `destroy-<workspace>` = destroy). `prod` pede a aprovação do Environment `producao`.

| Ordem | Repositório | Workflow | Tempo típico (estimado) | Como confirmar |
|---|---|---|---|---|
| 1 | `oficina-infra-k8s` | **Terraform** (`workspace=prod`) | ~5 min (k3s sobe no `user_data`) | `scripts/k3s-exec.sh prod "kubectl get nodes"` = `Ready` |
| 2 | `oficina-infra-database` | **Terraform** (`workspace=prod`) | ~8 min (criação do RDS) | `aws rds describe-db-instances` = `available` |
| 3 | `oficina-app` | **Deploy** (`main`) | ~6 min | `rollout status` verde + `/q/health/ready` |
| 4 | `oficina-auth-lambda` | **Deploy** (`confirm` vazio) | ~3 min | `POST $API/auth/token` devolve 200 |
| 5 | `oficina-infra-k8s` | **Observability** (opcional, exige conta New Relic) | ~2 min | dashboards no New Relic |

Total ~22 min. Os tempos da tabela são **estimativas da spec**: confirme-os na primeira sessão e corrija aqui (critério de aceite da E9).
Cada pipeline lê parâmetros SSM publicados pelo anterior; no push, os de app e auth-lambda **ignoram com aviso** se a infra ainda não
existe (verde sem ter aplicado); no disparo manual **falham de propósito**. Por isso a ordem acima não é opcional.

Para acompanhar uma execução: `gh run list -R victor-duarte-mendonca/<repo> -L 3` e `gh run view <id> --log-failed`.

## 4. Validação pós-subida

```
[ ] scripts/k3s-exec.sh prod "kubectl top nodes"                       → folga de memória para o HPA chegar ao teto
[ ] scripts/k3s-exec.sh prod "kubectl get pods,hpa -n oficina"         → pods Running, HPA lendo CPU
[ ] curl $API/health                                                    → 200
[ ] POST $API/auth/token com CPF semeado                                → 200 com token
[ ] GET  $API/me/work-orders com o token                                → 200
[ ] GET  $API/me/work-orders sem token                                  → 401 vindo do gateway
[ ] GET  $API/me/work-orders com token adulterado                       → 403 vindo do gateway
[ ] New Relic: entidade oficina-mecanica reportando; log com correlationId encontrável
[ ] Dashboards com dados; políticas de alerta ativas
```

`$API` = `aws ssm get-parameter --name /oficina/prod/api_gateway_url --query Parameter.Value --output text`. A checklist completa
(por etapa) está em [`VALIDACAO-PROD.md`](VALIDACAO-PROD.md).

## 5. Demonstração de escala (vídeo)

```bash
# O gateway limita a stage a 50 rps (burst 100): pela URL do gateway a carga vira 429 antes de cruzar 70% de CPU.
# Gere a carga direto no EIP (o security group libera a porta 80 para o seu IP /32), numa rota pública sem token:
EIP=$(aws ssm get-parameter --name /oficina/prod/k3s_eip --query Parameter.Value --output text)
hey -z 3m -c 50 "http://$EIP/public/work-orders/<numero-da-os>/status"
scripts/k3s-exec.sh prod "kubectl -n oficina get hpa"      # repetir; ou observar no dashboard "Operação"
```

Esperar scale-out em ~30 s (`behavior.scaleUp` sem janela de estabilização) e scale-in após 3 min de estabilização, uma réplica por
minuto ([ADR 0009](adr/0009-hpa-somente-cpu.md)). Se o quarto pod ficar `Pending`, o limite é a memória do node: reduza `maxReplicas` para 3
ou o request de memória. **Aquecer o Lambda antes de gravar**: uma invocação prévia elimina o cold start da demonstração.

## 6. Encerramento (não opcional)

Ordem inversa à subida, sempre com *Use workflow from* igual ao da subida e `confirm = destroy-<workspace>`:

```
[ ] oficina-infra-k8s     → Observability (destroy-prod)     (se foi aplicado)
[ ] oficina-auth-lambda   → Deploy (confirm = destroy-prod)
[ ] oficina-app           → nada a destruir (morre com o cluster)
[ ] oficina-infra-database→ Terraform (destroy-prod)         ← o mais caro se esquecido
[ ] oficina-infra-k8s     → Terraform (destroy-prod)
[ ] Varredura de órfãos (abaixo) volta vazia
[ ] Anotar o saldo final em docs/orcamento.md
[ ] End Lab
```

```bash
# Nada deve retornar
aws resourcegroupstaggingapi get-resources \
  --tag-filters Key=Project,Values=oficina --query 'ResourceTagMappingList[].ResourceARN'

# O que escapa de tag: EIPs soltos, volumes órfãos, snapshots, instâncias
aws ec2 describe-instances      --filters Name=instance-state-name,Values=running,stopped --query 'Reservations[].Instances[].InstanceId'
aws ec2 describe-addresses      --query 'Addresses[?AssociationId==`null`].PublicIp'
aws ec2 describe-volumes        --filters Name=status,Values=available --query 'Volumes[].VolumeId'
aws rds describe-db-instances   --query 'DBInstances[].DBInstanceIdentifier'
aws apigatewayv2 get-apis       --query 'Items[].Name'
aws lambda list-functions       --query 'Functions[].FunctionName'
aws ssm get-parameters-by-path  --path /oficina --recursive --query 'Parameters[].Name'
```

A API de tags demora a esquecer recursos já apagados: confirme pelos `describe-*`. Se o `destroy` falhar no meio (a ENI do Lambda costuma
segurar a subnet por alguns minutos), **rode de novo antes de encerrar a sessão**: um destroy parcial é a forma mais comum de deixar RDS e
EIP para trás.

## 7. Monitoramento do orçamento

- Conferir o crédito restante no painel do Academy **ao início e ao fim** de cada sessão e anotar em [`orcamento.md`](orcamento.md). Duas
  medições ruins revelam um vazamento antes que ele custe caro.
- Se o consumo de uma sessão passar de **US$1,00**, há recurso rodando fora do previsto: investigar antes da próxima sessão.
- Regra de corte: abaixo de **US$15** de saldo, `hml` deixa de subir e só produção é usada, pelo tempo estritamente necessário à gravação.

## 8. Modo econômico (se o crédito apertar)

Do menos ao mais doloroso:

1. Suspender as sessões de `hml`: validar mudança de infraestrutura direto em produção (registrar que foi decisão de orçamento).
2. `t3.small` em vez de `t3.medium` em produção (HPA máximo 2; o `nri-bundle` roda sem o prometheus-agent).
3. Reduzir a janela: subir, gravar o trecho, destruir.
4. Rodar tudo localmente (`docker compose`, [`RUN-LOCAL.md`](RUN-LOCAL.md)) e usar a AWS só para validação e gravação.

## 9. Falhas comuns

| Sintoma | Causa provável | O que fazer |
|---|---|---|
| `apply` vermelho com `security token ... invalid` | sessão do Academy expirada | renovar credenciais + `set-aws-session-secrets.sh` e *Re-run* |
| `plan` verde sem comentário de plano | sessão expirada (o `plan` é pulado com `notice`) | renovar credenciais e reexecutar o PR |
| Deploy de app/lambda verde sem aplicar | infra/SSM ainda não existe (ignora com aviso) | subir a infra e disparar o workflow **manualmente** |
| `ParameterNotFound` em `/oficina/prod/...` | etapa anterior não aplicada neste workspace | seguir a ordem da §3 |
| Pod `Pending` no teste de HPA | memória do node | `kubectl describe pod`; reduzir `maxReplicas` ou requests |
| Lambda sem acesso ao banco | `oficina_auth_ro` só existe depois da app migrar (V6) | implantar a app antes da função |
| `destroy` falha na subnet/ENI | ENI do Lambda ainda presa | aguardar alguns minutos e rodar de novo |
| `k3s-exec.sh` sem resposta | agente SSM ainda não registrou o node (k3s instalando) | aguardar ~5 min |
