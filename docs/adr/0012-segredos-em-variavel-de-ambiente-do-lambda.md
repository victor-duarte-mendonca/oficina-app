# ADR 0012 — Segredos em variável de ambiente do Lambda em vez de Secrets Manager

- **Status:** Aceito
- **Origem:** [`specs/02-auth-cpf-lambda.md`](../../specs/02-auth-cpf-lambda.md) §4

## Contexto

A função de autenticação, em VPC sem NAT (ADR 0005), precisa da senha do papel de banco e da chave privada JWT. Secrets Manager e
SSM são APIs públicas: sem saída para a internet, só são alcançáveis por VPC endpoint de interface (~US$7,20/mês por endpoint).

## Decisão

Os segredos da **função de autenticação** entram como **variáveis de ambiente do Lambda** (`DB_PASSWORD`, `JWT_PRIVATE_KEY_PEM`),
criptografadas em repouso pela chave gerenciada `aws/lambda` (custo zero) e entregues pelo Terraform a partir de GitHub Secrets do
Environment. Os endereços (`DB_HOST` etc.) são lidos do SSM pelo **Terraform** em tempo de `apply`, não em runtime.

O forwarder de logs e o authorizer estão **fora** da VPC e seguem outro caminho: o authorizer só recebe a chave **pública**
(não é segredo); o forwarder lê a licença do New Relic do SSM (SecureString) na partida a frio, sem guardá-la em variável.

## Consequências

- (+) Custo zero e sem dependência de rede em runtime.
- (−) **Quem tiver `lambda:GetFunctionConfiguration` lê os segredos.** Mitigações: o papel do banco é somente-leitura em 5 colunas, a
  chave privada é rotacionável por sessão e os valores nunca são impressos em log (o `plan` usa placeholders).
- (−) Os valores passam pelo state do Terraform (bucket S3 criptografado, acesso restrito).
- (−) Sem rotação automática nem auditoria de acesso por segredo, que o Secrets Manager daria.

## Alternativas consideradas

- **Secrets Manager / SSM via VPC endpoint:** ~US$7,20/mês por endpoint; inviável no orçamento.
- **NAT Gateway:** ~US$32/mês.
- **Lambda fora da VPC:** não alcança o RDS privado.

Quando outra abordagem seria melhor: orçamento de produção, em que Secrets Manager com rotação e VPC endpoint são o padrão.
