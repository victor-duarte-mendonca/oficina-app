# Spec 10 — Qualidade de código no CI (SonarCloud)

> Atende **R11** (validação de qualidade via SonarQube ou similar no CI). Complementa [08](08-testes-e-cobertura.md).
> **Responsabilidade do usuário:** criar a organização no SonarCloud, importar os 3 repositórios e fornecer o `SONAR_TOKEN` como secret.

## 1. Ferramenta (decisão)

**SonarCloud** (SaaS do SonarQube): gratuito para repositórios públicos — os 3 serviços são públicos ([ADR 0017](../../docs/adr/0017-repositorios-publicos.md)).
Alternativa: SonarQube Community em contêiner — exigiria uma instância permanente (memória no nó único ou mais um EC2); descartada.
O PDF aceita "SonarQube **ou similar**"; o README explica a equivalência.

## 2. Integração

- Análise **no workflow de CI** (job `quality`, depois de `build-test`): `mvn -B verify org.sonarsource.scanner.maven:sonar-maven-plugin:sonar`
  com `-Dsonar.projectKey`, `sonar.organization`, `sonar.coverage.jacoco.xmlReportPaths=target/site/jacoco/jacoco.xml`.
- `fetch-depth: 0` no checkout (blame e *new code*); `SONAR_TOKEN` em secret do repositório; PR de *fork* não roda (sem secret) — regra aceita.
- Em PR: análise de PR com decoração; em `homolog`/`main`: análise de branch.
- **Quality Gate bloqueante** como *status check* obrigatório do ruleset (nome do check: `sonar` / `SonarCloud Code Analysis`).

## 3. Quality Gate (customizado, por serviço)

| Condição | Limite |
|---|---|
| Cobertura em código **novo** | ≥ 80% |
| Cobertura **geral** | ≥ 80% (alinha com R10) |
| Duplicação em código novo | ≤ 3% |
| Issues novos: *bugs* e *vulnerabilities* | 0 (rating A) |
| *Security hotspots* revisados | 100% |
| *Maintainability* / *Reliability* / *Security* | A |
| Code smells novos | rating A (sem *blocker*/*critical*) |

Exclusões do Sonar (`sonar.exclusions`, `sonar.coverage.exclusions`) **espelham** as do JaCoCo ([08](08-testes-e-cobertura.md) §2) — mesma lista, mesma justificativa.

## 4. Outras verificações de qualidade/segurança no mesmo pipeline

Trivy (imagem; já existe), `gitleaks` (segredos no histórico do PR), OWASP Dependency-Check **ou** o scanner de dependências do
SonarCloud/Dependabot (alertas e PRs de atualização habilitados nos 3 repos). Resultados resumidos no README (badges).

## 5. Critérios de aceite

- [ ] Projeto Sonar por serviço, com *quality gate* configurado e **bloqueando** o merge (verificar abrindo um PR com bug proposital e vendo o check falhar).
- [ ] Badge do *quality gate* e da cobertura em cada README.
- [ ] Sem *hotspot* de segurança pendente e sem *blocker*/*critical* aberto no `main` de cada serviço no dia da entrega.
- [ ] Documentado em `docs/qualidade.md` como reproduzir a análise e onde ver o dashboard (link público no PDF).

## 6. Fora de escopo

Sonar auto-hospedado; *pull request decoration* em forks; métricas de duplicação entre serviços.
