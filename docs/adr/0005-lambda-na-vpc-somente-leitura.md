# ADR 0005 — Lambda dentro da VPC com acesso somente-leitura ao banco

- **Status:** Aceito
- **Origem:** [`specs/02-auth-cpf-lambda.md`](../../specs/02-auth-cpf-lambda.md) §4 e §8; [`specs/01-modelagem-banco.md`](../../specs/01-modelagem-banco.md) §3.4

## Contexto

A função de autenticação precisa consultar `clients` no RDS, que é `publicly_accessible = false`. A função fica exposta à
internet pelo gateway: o que ela puder fazer no banco, um atacante que a comprometa também poderá.

## Decisão

1. A função roda **dentro da VPC**, em subnets privadas, com security group próprio e regra de entrada 5432 no SG do RDS.
2. Conecta com o papel `oficina_auth_ro`, criado por migration, com `GRANT SELECT (id, name, cpf_cnpj, client_type, status) ON
   clients`. Não lê `email` nem `phone` e não pode `INSERT`.
3. **Sem NAT Gateway.** A função só fala com o RDS; a telemetria sai por CloudWatch e por um forwarder fora da VPC (ADR 0010).
4. `reserved_concurrent_executions` limita a concorrência para não esgotar as conexões do `db.t4g.micro`.

## Consequências

- (+) Comprometer a função não permite `DROP TABLE` nem ler dados além de 5 colunas.
- (+) Sem NAT: evita ~US$32/mês, dois terços do orçamento.
- (−) A função **não tem internet**: não alcança Secrets Manager/SSM em runtime (ADR 0012) nem envia telemetria direto.
- (−) Lambda em VPC acrescenta ENI e latência de cold start (mitigada por SnapStart, ADR 0016); `destroy` pode ser retardado
  pela liberação da ENI (rodar de novo).
- (−) A conta Academy não permite criar IAM role: usa-se a `LabRole`, cuja trust policy e permissões de ENI precisam ser
  validadas na primeira sessão.

## Alternativas consideradas

- **Lambda fora da VPC:** não alcança o RDS privado; tornar o RDS público seria pior.
- **Usuário mestre do RDS:** dá `DROP TABLE` a uma função exposta.
- **NAT Gateway ou VPC endpoints:** custo mensal incompatível com o orçamento.
- **Lambda consultando a API da aplicação em vez do banco:** plano B do risco de `LabRole`; acopla a autenticação à
  disponibilidade do cluster.
