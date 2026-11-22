# Spec 11 — CI/CD independente por serviço e repositórios protegidos

> Atende **R12** (pipeline independente: build, testes, qualidade e deploy automatizado em Kubernetes) e **R13** (`main` com PR obrigatório e checagens automáticas).
> Reaproveita [`08-repositorios-cicd.md`](../08-repositorios-cicd.md) (Fase 3). Qualidade: [10](10-qualidade-sonar.md); testes: [08](08-testes-e-cobertura.md).
> **Responsabilidade do usuário:** criar os dois repositórios na organização, `gh auth`, secrets/valores, aprovar o Environment `producao`, merges de PR.

## 1. Repositórios e proteção (R13)

| Repo | Criação | Proteção |
|---|---|---|
| `oficina-billing-service`, `oficina-execution-service` | organização `victor-duarte-mendonca`, **públicos** ([ADR 0017](../../docs/adr/0017-repositorios-publicos.md)) | ruleset idêntico ao da Fase 3 |
| `oficina-app` | já existe | ruleset atual + checks novos |

Ruleset (em `main` e `homolog`): sem push direto/force-push/exclusão; **PR obrigatório** (0 aprovações exigidas — regra vigente
porque o repositório é de uma pessoa; o ruleset segue exigindo PR e *status checks*); *squash merge*; descartar aprovações obsoletas; *checks* obrigatórios por
repositório: `build-test`, `quality` (Sonar), `docker-build`, `contract` (quando houver), `workflow-lint`. Environments `homologacao`
e `producao` (este com aprovação manual do usuário). Script `scripts/bootstrap-repo.sh` automatiza ruleset + environments + dependabot (idempotente, executado pelo usuário com `gh auth`).
Branches: `feature/*` → PR → `homolog` → PR → `main` (spec 08 §2). Dependabot e *secret scanning* habilitados.

## 2. Pipeline por serviço (igual nos três, parametrizado)

```
PR / push
  build-test      mvn verify (unidade + componente + BDD + gate JaCoCo BUNDLE ≥ 80%) → artefatos de relatório
  quality         SonarCloud (quality gate bloqueante) + gitleaks
  contract        valida schemas/exemplos de mensagens (sync-contracts.sh --check)
  docker-build    build + Trivy (CRITICAL/HIGH corrigíveis) — sem push
  workflow-lint   actionlint + shellcheck (+ kustomize build dos overlays)

push em homolog / main  (após os checks)
  docker-push     imagem tag = SHA no ECR do serviço (ECR por serviço, de oficina-infra-k8s)
  deploy          kustomize overlay do ambiente → kubectl apply via túnel SSM (composite action k3s-kubeconfig)
                  rollout status → rollback automático (kubectl rollout undo) se falhar
  smoke-test      /q/health/{live,ready}; consumo/produção de uma mensagem de teste na fila; rota protegida via gateway
```

- **Independência**: cada repositório tem o seu `.github/workflows/{ci,deploy}.yml`; nenhum workflow depende de outro repositório.
  A composite action `k3s-kubeconfig` é **copiada** em cada repositório de serviço (não existe repositório de ações compartilhadas — evita um 6º repositório; mesma decisão da Fase 3).
- **Deploy não executa Terraform**: apenas a aplicação. Se a infraestrutura (cluster, filas, banco) ainda não existe, o deploy
  **passa verde com aviso** (comportamento já validado na Fase 3).
- **Ambientes**: `homolog` → `homologacao`/hml; `main` → `producao`/prod (aprovação manual). Config sensível do SSM, nunca copiada à mão.
- **Variáveis de pipeline** lidas do SSM (`/oficina/{env}/...`): URL do ECR, endpoint do banco, nomes/URLs das filas, nome da tabela.
- **Rollback** de cada serviço é independente; como as mensagens são versionadas e a V7 é aditiva, uma versão anterior de **um** serviço convive com a atual dos outros.
- **Ordem de deploy numa mudança de contrato** (documentada no README): consumidor primeiro, produtor depois; remoção só em versão posterior.
- Cache Maven, `concurrency` por ref, `permissions: contents: read` mínimas — como no `ci.yml` atual.

## 3. Evidência de "deploy automatizado com validação de testes" (vídeo)

Um PR em `homolog` num serviço mostra: checks `build-test`/`quality` verdes → merge → *workflow* de deploy → rollout → *smoke*.
Deve existir **um caso de falha** demonstrável (teste quebrado bloqueia o merge; imagem inválida aciona rollback em hml).

## 4. Critérios de aceite

- [ ] Os três repositórios com ruleset verificado (tentativa de push direto na `main` rejeitada; merge bloqueado com check vermelho).
- [ ] CI verde nos três; *quality gate* e cobertura exibidos no PR.
- [ ] Deploy de **cada** serviço executado isoladamente em hml e prod, sem tocar nos outros (`kubectl rollout history` por Deployment).
- [ ] Rollback automático exercitado ao menos uma vez (fecha a pendência da Fase 3).
- [ ] `actionlint`/`shellcheck` limpos; nenhum secret impresso nos logs (`::add-mask::` onde aplicável).
- [ ] README de cada repositório descreve a pipeline, os checks obrigatórios e como reproduzir localmente.

## 5. Fora de escopo

GitOps (Argo CD/Flux); *canary*/*blue-green*; repositório de ações compartilhadas; promoção automática `homolog` → `main`.
