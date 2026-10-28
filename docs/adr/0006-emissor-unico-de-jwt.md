# ADR 0006 — Emissor único de JWT: mesmo par de chaves para aplicação e Lambda

- **Status:** Aceito
- **Origem:** [`specs/02-auth-cpf-lambda.md`](../../specs/02-auth-cpf-lambda.md) §2

## Contexto

Dois componentes emitem tokens: a aplicação (login de funcionário, papéis `ADMIN` e `MECHANIC`) e o Lambda (cliente, papel
`CLIENT`). A aplicação já valida JWT RS256 com `mp.jwt.verify.publickey.location`.

## Decisão

Os dois assinam com a **mesma chave privada RS256** e o **mesmo `iss` (`oficina-api`)**. A aplicação valida o token do Lambda sem
configuração nova; o authorizer do gateway (ADR 0007) valida com a **chave pública**, derivada da privada pelo Terraform. O
papel `CLIENT` é novo e sem interseção com `ADMIN`/`MECHANIC`.

## Consequências

- (+) Zero configuração extra de validação; um único domínio de confiança.
- (+) O authorizer não precisa de segredo, só da chave pública.
- (−) A chave privada existe em dois lugares (Secret do Kubernetes e variável do Lambda, ADR 0012): quem a lê emite token de
  qualquer papel. Tratada como segredo e gerada **uma vez** (`generate-keys.sh`), com o mesmo valor nos dois repositórios.
- (−) Rotação exige trocar a chave nos dois lados ao mesmo tempo; tokens emitidos antes deixam de valer.

## Alternativas consideradas

- **Dois emissores, duas chaves:** exigiria multi-issuer no SmallRye sem ganho, já que os dois pertencem ao mesmo domínio de
  confiança.
- **Lambda chamando a aplicação para emitir o token:** acopla autenticação à disponibilidade do cluster.
- **Cognito como emissor único:** adequado a longo prazo (ADR 0004), mas fora do escopo e do orçamento desta fase.
