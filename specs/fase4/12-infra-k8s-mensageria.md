# Spec 12 — Infraestrutura: Kubernetes, mensageria, bancos e gateway

> Atende **R14** (mensageria no ambiente Kubernetes) e a metade "infraestrutura própria" de **R1**/**R12**. Decisões abertas: **D2** (SQS×RabbitMQ), **D3** (RDS do Billing), tamanho do nó.
> Contrato entre repositórios por SSM ([ADR 0011](../../docs/adr/0011-contrato-por-ssm.md)); credenciais e valores sempre do usuário. Antes de qualquer `plan`/`apply`: sessão Academy ativa; `destroy` ao final (CLAUDE.md do workspace).

## 1. O que cada repositório de infraestrutura passa a provisionar

| Repositório | Novo | Publica no SSM (`/oficina/{env}/...`) |
|---|---|---|
| `oficina-infra-k8s` | 2 repositórios ECR (`billing`, `execution`); **filas SQS FIFO + DLQs** (`messaging.tf`); ajuste de tamanho do nó; regras de rede; (opcional) parâmetros de observabilidade | `ecr_billing_url`, `ecr_execution_url`, `sqs/billing_commands_url`, `sqs/execution_commands_url`, `sqs/os_replies_url` (e as DLQs) |
| `oficina-infra-database` | **RDS PostgreSQL do Billing** (módulo reutilizado do RDS atual, instância/SG/parameter group próprios, TLS obrigatório); **tabela DynamoDB** da Execução (+ GSIs, TTL) | `billing/db_endpoint`, `billing/db_name`, `execution/dynamodb_table` |
| `oficina-auth-lambda` | rotas do API Gateway (§5); *throttling* dedicado ao webhook | `application_url` já existente |

Os *workspaces* `hml`/`prod`, o *state* no S3 com `use_lockfile` e o fluxo `plan`/`apply`/`destroy` por workflow seguem iguais.
O `destroy` precisa remover filas e tabela (conferir órfãos: `list-queues`, `list-tables`, `describe-db-instances`).

## 2. Mensageria no cluster (R14)

"Mensageria para orquestração ou coreografia" é satisfeita por **SQS gerenciado** consumido pelos pods do k3s (D2 recomendada).
Se **D2 = RabbitMQ**: `StatefulSet` com volume, `rabbitmq_prometheus`, ~256–512 MiB, *quorum queues* — o impacto de memória entra no §3.

Filas (ver [03](03-mensageria-e-contratos.md) §1): `billing-commands`, `execution-commands`, `os-saga-replies`, todas `.fifo`, `content_based_deduplication=false`
(dedupe explícito), DLQs `.fifo` com `maxReceiveCount=5`, SSE-SQS ligado.

## 3. Capacidade do nó (**spike obrigatório antes de codar os serviços**)

Medição da Fase 3: `t3.medium` (4 GiB) a ~65% de memória com **1** réplica da app + 6 pods do New Relic. Três JVMs Quarkus
(2 réplicas cada no HPA da Fase 3) não cabem. Passos do spike (F2):

1. Medir RSS real de uma imagem de cada serviço sob carga leve (`kubectl top`); testar `-Xmx`/`MaxRAMPercentage` e `requests` menores.
2. Reduzir `minReplicas` de Billing e Execution para **1** (HPA 1→2 por CPU) e manter o OS em 2→4.
3. Regra de decisão: se a soma dos *requests* + sistema > 80% da memória, subir para **`t3.large`** (8 GiB, ≈ US$0,083/h) via
   `instance_type_override` — custo extra de uma sessão de 4 h ≈ US$0,17. *(Confirmar no Academy se `t3.large` é permitido: o Learner Lab costuma limitar o porte.)*
4. `hml` hoje usa `t3.small` (sem o `nri-bundle` completo): **não comporta** 3 serviços; em hml rodar os 3 com `replicas: 1` num `t3.medium` ou validar só um serviço por vez.
5. Registrar a medição em `docs/orcamento.md` e atualizar o [ADR 0002](../../docs/adr/0002-k3s-em-ec2-unica.md) se o tamanho mudar.

Alternativa se a memória for o gargalo definitivo: **build nativo** (GraalVM) dos serviços novos (~60–100 MiB), ao custo de build longo no CI — só se o spike indicar.

## 4. Rede e isolamento

- **Namespaces**: manter `oficina` e rotular os Deployments (`app=oficina-os|billing|execution`); `NetworkPolicy` por serviço (ingress só do Traefik e do vizinho necessário; egress para DNS, RDS/DynamoDB/SQS/Stripe/New Relic).
- **RDS do Billing**: security group aceita 5432 **só** do SG do nó (mesmo padrão do RDS do OS); como o nó é um só, a separação real entre o acesso do OS e do Billing é feita por **credenciais distintas** (usuário/senha por serviço em Secrets distintos) — registrar a limitação no README.
- **Egress à Stripe**: o nó está em subnet pública com IP público ⇒ saída à internet existe sem NAT; confirmar na F2 com um `curl` ao `api.stripe.com` a partir de um pod.
- **VPC endpoints** para SQS/DynamoDB **não** são necessários (tráfego sai pelo IP público do nó) e custariam por hora; descartados.

## 5. Gateway e Ingress

API Gateway atual: rotas `ANY /admin/{proxy+}`, `ANY /me/{proxy+}`, `GET /public/{proxy+}`, `GET /me`, `POST /auth/*`, `GET /health`, integração HTTP para o EIP.

| Mudança | Detalhe |
|---|---|
| Rotas existentes | continuam; **o roteamento por serviço é feito no Traefik** (Ingress por serviço com `pathPrefix`: `/admin/budgets`, `/me/budgets`, `/public/budgets` → Billing; `/admin/executions` → Execution; resto → OS). Traefik escolhe o prefixo mais específico |
| `POST /public/{proxy+}` | **nova** (a rota pública atual é só `GET`), para a decisão por link do e-mail; sem authorizer; *throttling* próprio |
| `POST /webhooks/stripe` | **nova**, sem authorizer JWT (a autenticação é a assinatura, [05](05-billing-service-mercado-pago.md) §5); throttling baixo (ex.: 20 rps, burst 40); corpo ≤ 16 KB |
| `X-Gateway-Key` | enviado pelo gateway a todas as integrações; **o webhook também passa pelo gateway**, então segue protegido contra acesso direto ao EIP |
| URL do webhook | muda a cada sessão (Academy); a Stripe guarda a URL **no endpoint**: o deploy a atualiza pela API (S3), o `whsec_` não muda |

Verificações da F2: o contrato do authorizer (`403` com token recusado, `401` sem header) e os testes de throttling da Fase 3 continuam passando.

## 6. Credenciais AWS dos pods — ponto de atenção (ADR 0022, Proposto)

Hoje o IMDS está com `http_put_response_hop_limit = 1`, de propósito: **pods não alcançam as credenciais do `LabRole`** (defesa contra SSRF). Os
serviços novos precisam chamar SQS e DynamoDB, e o Academy **não** oferece IRSA/OIDC nem usuários IAM persistentes (credenciais
da sessão expiram). Opções:

| Opção | Prós | Contras |
|---|---|---|
| **A. `hop_limit = 2` + `NetworkPolicy` que só libera o IMDS (169.254.169.254) aos pods dos 3 serviços** (recomendada) | credencial renovada automaticamente; superfície limitada | todo pod de serviço passa a poder usar o `LabRole` inteiro (sem segregação IAM por serviço); depende da política de rede ser aplicada pelo k3s (kube-router) |
| B. credenciais da sessão copiadas para um Secret | sem mudar a EC2 | expiram em horas; manual a cada sessão; vazamento de token em Secret |
| C. proxy/sidecar de credenciais | segregação possível | complexidade desproporcional |

Escolhida **A**, com teste explícito: pod de *outro* tipo (ex.: um `curlimages/curl` temporário) **não** alcança o IMDS; pod de serviço sim.
Limitação a documentar: "IAM por serviço" não é viável no Academy (`LabRole` único). Em AWS real: IRSA/EKS Pod Identity.

## 7. Operação (runbook da Fase 4 — estende o `RUNBOOK.md`)

Ordem de subida: infra-k8s (+ filas) → infra-database (RDS billing + DynamoDB) → segredos (Stripe no SSM, **do usuário**) → deploy OS/Billing/Execution → auth-lambda (rotas) → Observability.
Ordem de encerramento: Observability → auth-lambda → infra-database → infra-k8s → **varredura de órfãos incluindo filas e tabelas**.
Reprocesso de DLQ: `aws sqs start-message-move-task` (ou script) para a fila de origem após corrigir a causa; nunca apagar sem registrar `messageId` no log.
Medições de custo/tempo na `orcamento.md` (RDS adicional ≈ US$0,016/h; SQS/DynamoDB desprezíveis).

## 8. Critérios de aceite

- [ ] `terraform fmt/validate/tflint/trivy` limpos nos três repositórios de infra; `plan`/`apply` em hml e prod; `destroy` conferido sem órfãos (incluindo filas, tabela e segundo RDS).
- [ ] Parâmetros SSM novos publicados e lidos pelos deploys (nada copiado à mão).
- [ ] Spike de capacidade registrado; nó dimensionado com ≥ 20% de folga de memória com os 3 serviços e os agentes.
- [ ] Teste de credencial dos pods (§6): serviço consegue `SendMessage`; pod avulso não alcança o IMDS.
- [ ] Mensagem de teste atravessa cada fila; falhas levam à DLQ e à métrica.
- [ ] Rotas novas do gateway passam nos testes de throttling e de acesso direto ao EIP (403 sem `X-Gateway-Key`).

## 9. Fora de escopo

EKS; NAT Gateway; VPC endpoints; multi-AZ/HA do nó ou do RDS; WAF; certificado/domínio (HTTPS fim a fim fica como na Fase 3).
