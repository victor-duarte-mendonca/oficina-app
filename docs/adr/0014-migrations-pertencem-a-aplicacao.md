# ADR 0014 — Migrations de schema pertencem à aplicação, não ao repositório de infraestrutura

- **Status:** Aceito
- **Origem:** [`specs/01-modelagem-banco.md`](../../specs/01-modelagem-banco.md) §3.4

## Contexto

Com o split em quatro repositórios, o RDS é provisionado por `oficina-infra-database` e o schema evolui junto do código em
`oficina-app`. A migration `V6` cria o papel somente-leitura `oficina_auth_ro` usado pelo Lambda: é DDL, mas o consumidor está em
outro repositório.

## Decisão

- **`oficina-infra-database` provisiona a instância** (RDS, subnet group, parameter group, SG, contrato SSM) e **nada mais**: não
  cria tabelas, papéis nem dados.
- **`oficina-app` é dono do schema** (Flyway, `V1`–`V6`): inclusive o papel `oficina_auth_ro`, com `GRANT SELECT` por coluna. A senha
  entra por **placeholder do Flyway** (`lambda_ro_password`), alimentado pelo Secret do Kubernetes; nunca literal na migration.
- As migrations rodam **no start de cada pod** (`QUARKUS_FLYWAY_MIGRATE_AT_START`). O Flyway serializa por advisory lock do
  PostgreSQL, então N réplicas subindo juntas é seguro.

## Consequências

- (+) Schema e código evoluem no mesmo PR e na mesma versão; teste de integração com PostgreSQL real exercita as migrations.
- (+) A infraestrutura de banco não precisa de acesso SQL nem de driver.
- (−) **Dependência de ordem:** o papel `oficina_auth_ro` só existe depois que a aplicação migra o banco, então o smoke test da função
  Lambda só passa com a app implantada no mesmo ambiente.
- (−) Migration que roda no start do pod atrasa o readiness e, se falhar, o rollout falha. O padrão cloud-native seria um Job ou
  initContainer dedicado; aqui o ganho não paga a complexidade de uma imagem de migração separada.
- (−) A senha do papel precisa ser a **mesma** na aplicação e no Lambda (GitHub Secrets nos dois repositórios).

## Alternativas consideradas

- **Papel criado pelo Terraform do banco:** exigiria provider PostgreSQL com rede até o RDS privado (túnel) dentro da pipeline de
  infra, e dividiria a propriedade do schema.
- **Job de migração dedicado:** separa responsabilidade e evita atraso de start, ao custo de mais um artefato.
- **Aplicação manual de SQL:** não reprodutível.
