# RFC 0001 — Escolha da nuvem

- **Status:** Aprovado (recomendação adotada)
- **Decisão derivada:** [ADR 0002](../adr/0002-k3s-em-ec2-unica.md), [ADR 0003](../adr/0003-api-gateway-http-api.md)

## 1. Problema

Escolher onde hospedar API Gateway, Function Serverless, banco gerenciado e cluster Kubernetes da Fase 3, com Terraform, dentro de um
orçamento fixo e de um ambiente de laboratório com restrições próprias.

## 2. Restrições

- **Crédito do AWS Academy: ~US$48** (o painel fala em US$50), único para o curso, sem recarga; esgotado, a conta é desativada.
- **IAM bloqueado:** `iam:CreateRole` e `iam:CreatePolicy` são negados; só se usa a `LabRole` e o `LabInstanceProfile` existentes.
- **"End Lab" só para EC2.** RDS, EIP e API Gateway continuam existindo e cobrando.
- **Sessões de 4 horas** com credenciais STS que expiram; a conta é reciclada (o ID da conta muda).
- A Fase 2 já tem **todo o Terraform e o pipeline em AWS** (VPC, EC2/k3s, RDS, ECR).

## 3. Opções

| Critério | AWS (Academy) | Azure | GCP |
|---|---|---|---|
| Continuidade da Fase 2 | **Total** (Terraform, pipeline e runbook existem) | Reescrita de toda a infra | Reescrita de toda a infra |
| Crédito disponível | ~US$48 do Learner Lab | Créditos de conta nova, de outra natureza | Créditos de conta nova, de outra natureza |
| API Gateway + Function + banco + K8s | HTTP API, Lambda, RDS, k3s/EKS | API Management, Functions, Azure DB, AKS | API Gateway, Cloud Functions, Cloud SQL, GKE |
| Restrições conhecidas | IAM bloqueado, End Lab parcial | Desconhecidas para este projeto | Desconhecidas para este projeto |
| Custo de migração | Zero | Alto (sem ganho funcional) | Alto (sem ganho funcional) |

## 4. Recomendação

**AWS, no Academy Learner Lab.** Todo o Terraform já existe e foi validado; migrar para Azure ou GCP seria reescrita sem ganho para
nenhum requisito do PDF. As restrições do Academy são conhecidas e contornáveis:

- IAM bloqueado → usar `LabRole`/`LabInstanceProfile` (`data "aws_iam_role"`), sem criar papéis; o forwarder de logs próprio existe por
  causa disso (o do New Relic cria role).
- Orçamento → escolher serviços **sem custo por hora** (HTTP API, Lambda, SSM standard) e k3s em vez de EKS (ADR 0002).
- "End Lab" parcial → `destroy` obrigatório no fim da sessão, com varredura de órfãos (runbook).

## 5. Riscos e pontos em aberto

- A conta pode bloquear `apigateway:*` ou a `LabRole` pode não aceitar `lambda.amazonaws.com`/ENI: validação na primeira sessão AWS
  (planos B nas specs 03 e 02).
- Dependência de uma conta que **expira**: tudo é reconstruível a cada sessão por Terraform e pipelines.

## 6. Contestação

Se o crédito do Academy acabar, a alternativa é migrar para outra nuvem com créditos novos; o custo seria a reescrita do Terraform
(VPC, compute, banco, gateway), não da aplicação, que é portável (container + PostgreSQL).
