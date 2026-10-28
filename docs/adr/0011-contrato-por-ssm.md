# ADR 0011 — Contrato entre repositórios por SSM Parameter Store

- **Status:** Aceito
- **Origem:** [`specs/06-infra-k8s-terraform.md`](../../specs/06-infra-k8s-terraform.md) §3

## Contexto

Quatro repositórios, quatro stacks de deploy independentes. O auth-lambda precisa da VPC e do EIP; o banco precisa da VPC e do SG
do cluster; a aplicação precisa do endpoint do RDS e do ECR. Cada um aplica no seu tempo.

## Decisão

Cada repositório **publica** seus endereços como parâmetros *standard* do SSM Parameter Store (gratuitos), sob
`/oficina/<env>/…`, e os demais **leem** em tempo de `apply`/deploy:

| Publicado por | Parâmetros |
|---|---|
| `oficina-infra-k8s` | `vpc_id`, `private_subnet_ids`, `k3s_security_group_id`, `k3s_eip`, `ecr_repository_url`, `application_url`; opcional `newrelic_license_key` (SecureString) |
| `oficina-infra-database` | `db_endpoint`, `db_port`, `db_name`, `rds_security_group_id` |
| `oficina-auth-lambda` | `api_gateway_url` |

Sem credenciais nos parâmetros: só endereços. Usuário e senha seguem como GitHub Secrets. A ordem de subida decorre do contrato:
infra-k8s → infra-database → app → auth-lambda; os pipelines de deploy **ignoram com aviso** (push) ou **falham** (disparo manual)
se o contrato ainda não existe.

## Consequências

- (+) Repositórios desacoplados: nenhum lê o state do outro.
- (+) Gratuito e auditável (`aws ssm get-parameters-by-path`).
- (−) Contrato implícito: renomear um parâmetro quebra o consumidor só em tempo de execução (mitigado pelo `stack` check do
  auth-lambda, que lista os nomes esperados).
- (−) Qualquer identidade da conta lê os parâmetros: por isso não há segredos neles.
- (−) Valores obsoletos: um `destroy` parcial pode deixar parâmetros de uma stack já removida.

## Alternativas consideradas

- **`terraform_remote_state`:** exigiria acesso de leitura ao bucket de state, e o state do banco contém a **senha do RDS em
  texto**. Dar esse acesso para publicar um endpoint troca 6 strings por um vazamento de credencial.
- **Outputs copiados à mão entre repositórios:** frágil, não auditável.
- **Terraform monolítico:** viola R4 (repositórios separados com deploy independente).
