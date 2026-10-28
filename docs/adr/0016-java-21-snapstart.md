# ADR 0016 — Java 21 + SnapStart como runtime da Function Serverless

- **Status:** Aceito (decisão fechada em 2026-09-09)
- **Origem:** [`specs/02-auth-cpf-lambda.md`](../../specs/02-auth-cpf-lambda.md) §3

## Contexto

A função de autenticação roda em VPC (ADR 0005) e precisa assinar RS256, falar JDBC com TLS e iniciar rápido. O time domina Java/Maven;
cold start de JVM em Lambda é o ponto fraco clássico.

## Decisão

**Java 21 com SnapStart**, empacotado como **ZIP** (Maven Shade), sem framework (o handler é uma classe simples; `Composition` monta as
dependências). Dependências: `aws-lambda-java-core/events`, driver PostgreSQL, `nimbus-jose-jwt`, HikariCP. SnapStart exige **versão
publicada + alias** (`live`), não funciona em `$LATEST` e proíbe imagem de container; a conexão JDBC **não** é aberta na inicialização
(o socket não sobrevive à restauração), e sim sob demanda no handler. O authorizer (outra função do mesmo repositório) usa o mesmo
runtime, mas fora da VPC.

## Consequências

- (+) Um único toolchain (Maven) em todo o projeto; reuso conceitual do domínio (CPF, JOSE).
- (+) SnapStart é gratuito para Java e leva o cold start de ~2–4 s para a casa de ~400 ms (**a medir na sessão AWS**; meta < 1 s).
- (−) Pipeline com passos a mais (`publish-version` e movimentação do alias; no Terraform, `publish = true`).
- (−) Artefato ZIP: o `Dockerfile` do repositório existe só para build reprodutível, não para o runtime.
- (−) SnapStart pode não estar disponível na conta Academy: plano B é Node.js.

## Alternativas consideradas

| Opção | Por que não |
|---|---|
| Node 22 / Python 3.13 | Cold start menor (~300 ms) e menos linhas (~90 vs ~200), mas um **segundo toolchain** para manter, testar e escanear, e reimplementação do domínio |
| Java 21 sem SnapStart | Cold start de 2–4 s em VPC |
| Quarkus/Spring dentro do Lambda | Peso morto de cold start e de superfície de CVE para ~200 linhas |
| Imagem de container | Incompatível com SnapStart |
