# Deploy no k3s — Oficina Mecânica

## Deploy automatizado (o caminho normal)

O deploy é feito pelo workflow [`Deploy`](../.github/workflows/deploy.yml): **imagem no ECR** (tag = SHA do
commit, nunca `:latest`, com scan Trivy antes do push) → **túnel SSM** até o k3s → secrets criados a partir dos
secrets do GitHub Environment → `kubectl apply -k k8s/overlays/<env>` → `rollout status` com **rollback
automático** → **smoke test** ([`scripts/smoke-test.sh`](../scripts/smoke-test.sh)) e health check pelo Ingress
de dentro do node.

| Branch | Environment | Workspace |
|---|---|---|
| `homolog` | `homologacao` | `hml` |
| `main` | `producao` (aprovação do usuário) | `prod` |

**A stack sobe sob demanda.** Um push que mude a aplicação só dispara o deploy se a stack existir; sem ela (ou
com a sessão AWS expirada), o deploy é **ignorado com um aviso**, sem deixar o merge vermelho. Depois de subir a
infraestrutura (`oficina-infra-k8s` e `oficina-infra-database` no mesmo workspace), dispare à mão:
*Actions → Deploy → Run workflow → Use workflow from: `homolog`*. O ECR é recriado a cada sessão, então a imagem
é reconstruída e enviada em cada disparo.

**Secrets de environment** (`homologacao` / `producao`; valores são do usuário):

| Secret | Obrigatório | Observação |
|---|---|---|
| `DB_USERNAME`, `DB_PASSWORD` | sim | os mesmos do `oficina-infra-database` |
| `LAMBDA_RO_PASSWORD` | sim | senha do papel `oficina_auth_ro` (migration V6); a **mesma** do `oficina-auth-lambda` |
| `JWT_PRIVATE_KEY_PEM` | sim | chave RSA PKCS#8; a **mesma** do Lambda. A pública é derivada no deploy |
| `APP_GATEWAY_KEY` | `prod` | `X-Gateway-Key` que o API Gateway injeta; em `hml` vazio = filtro desligado |
| `APP_SEED_ADMIN_PASSWORD`, `APP_SEED_MECHANIC_PASSWORD` | `hml` | o seed só está ligado em homologação |
| `MAILER_HOST`, `MAILER_USERNAME`, `MAILER_PASSWORD` | não | sem SMTP a aprovação remota por e-mail não funciona |

O `endpoint` do RDS e a URL do ECR vêm do **SSM** publicado pelos repositórios de infraestrutura; nada é copiado
à mão. O `secret.example.yaml` da base fica **fora** do kustomization de propósito: se estivesse nos
`resources`, cada `kubectl apply -k` sobrescreveria o Secret real com os placeholders.

---

## Deploy manual (referência)

Pré-requisito: `KUBECONFIG` apontando para o cluster k3s (ver [`../infra/README.md`](../infra/README.md),
seção "Buscar o kubeconfig").

## Manifestos

| Arquivo               | Recurso                                                       |
|-----------------------|---------------------------------------------------------------|
| `base/` + `overlays/{hml,prod}` | Kustomize: a base tem os valores de produção; cada overlay sobrescreve réplicas/HPA, seed e nome do ambiente |
| `namespace.yaml`      | Namespace `oficina`                                           |
| `configmap.yaml`      | Configuração não sensível (perfil, CORS, SMTP, seed)          |
| `secret.example.yaml` | **Exemplo** (fora do kustomization) — os valores reais vêm da pipeline ou dos comandos abaixo |
| `deployment.yaml`     | ServiceAccount + Deployment + PodDisruptionBudget             |
| `service.yaml`        | Service ClusterIP                                             |
| `ingress.yaml`        | Ingress Traefik — é o que expõe a API para fora do cluster    |
| `hpa.yaml`            | HorizontalPodAutoscaler (2–4 réplicas, CPU 70%)               |
| `networkpolicy.yaml`  | Segmentação de rede (inerte no flannel padrão do k3s)         |

## 1. Base (namespace + config não sensível)

```bash
kubectl apply -f k8s/namespace.yaml -f k8s/configmap.yaml
```

## 2. Secret do ECR (token de 12h — regenerar se a sessão passar disso)

```bash
kubectl create secret docker-registry ecr-registry-secret \
  --namespace oficina --docker-server=$(terraform -chdir=../infra output -raw ecr_repository_url) \
  --docker-username=AWS --docker-password="$(aws ecr get-login-password --region us-east-1)"
```

No PowerShell (Windows), equivalente:
```powershell
$ecrUrl = terraform -chdir=../infra output -raw ecr_repository_url
$ecrPassword = aws ecr get-login-password --region us-east-1
kubectl create secret docker-registry ecr-registry-secret --namespace oficina --docker-server=$ecrUrl --docker-username=AWS --docker-password=$ecrPassword
```

## 3. Chaves JWT (uma vez por sessão, compartilhadas entre réplicas)

Gera localmente (nunca commitar `keys/*.pem`, já estão no `.gitignore`):

```bash
cd .. && ./generate-keys.sh && cd k8s
kubectl create secret generic oficina-jwt-keys --namespace oficina \
  --from-file=privateKey.pem=../keys/privateKey.pem \
  --from-file=publicKey.pem=../keys/publicKey.pem
```

```powershell
kubectl create secret generic oficina-jwt-keys --namespace oficina --from-file=privateKey.pem=..\keys\privateKey.pem --from-file=publicKey.pem=..\keys\publicKey.pem
```

> O Secret é montado como **volume** (não variável de ambiente) em `/app/keys`, para que todas as
> réplicas assinem com o mesmo par. Sem isso, cada pod geraria a própria chave no entrypoint e um token
> emitido por um pod seria rejeitado pelos outros.

## 4. Secret da aplicação (DB + SMTP + seed)

```bash
kubectl create secret generic oficina-secrets --namespace oficina \
  --from-literal=DB_HOST=$(terraform -chdir=../infra output -raw rds_endpoint) \
  --from-literal=DB_USERNAME=oficina_admin \
  --from-literal=DB_PASSWORD='<mesma senha do TF_VAR_db_password>' \
  --from-literal=MAILER_HOST='<host SMTP>' \
  --from-literal=MAILER_USERNAME='<usuario SMTP>' \
  --from-literal=MAILER_PASSWORD='<senha SMTP>' \
  --from-literal=APP_SEED_ADMIN_PASSWORD='<senha do admin>' \
  --from-literal=APP_SEED_MECHANIC_PASSWORD='<senha do mecanico>' \
  --from-literal=APP_GATEWAY_KEY='<mesma chave configurada no API Gateway>' \
  --from-literal=LAMBDA_RO_PASSWORD='<senha do papel oficina_auth_ro (migration V6)>'
```

> `APP_GATEWAY_KEY` ativa o `GatewayKeyFilter`: com ela, `/admin/*` e `/me/*` só respondem a requisições
> que tragam o header `X-Gateway-Key` igual (o API Gateway o injeta). Vazia, o filtro fica desligado.
> `DB_HOST` vem do SSM (`/oficina/<env>/db_endpoint`) e a URL JDBC de produção já usa `sslmode=require`.

> ⚠️ **O SMTP deixou de ser opcional.** A aprovação pública do orçamento exige um código de uso único
> que só é entregue por e-mail. Sem SMTP funcionando, o cliente não consegue aprovar remotamente — resta
> o canal administrativo (`PATCH /admin/work-orders/{id}/approve`), que registra a decisão tomada
> presencialmente. A falha de envio continua não derrubando a transição de status.

## 5. Deployment, service, ingress, HPA e network policy (kustomize)

Os manifestos ficam em `k8s/base` e são parametrizados por **kustomize** (sem `sed` no YAML). Cada
ambiente é um cluster distinto ([spec 06](../specs/06-infra-k8s-terraform.md) §4), com o mesmo namespace
`oficina`; o que muda é o overlay:

| | `overlays/hml` | `overlays/prod` |
|---|---|---|
| Réplicas / HPA | 1 → 2 | 2 → 4 |
| `APP_SEED_ENABLED` | `true` (demonstração) | `false` |
| `APP_ENV` (campo `env` nos logs) | `hml` | `prod` |
| `OTEL_TRACES_SAMPLER_ARG` (fração de traces; só vale com `NEW_RELIC_LICENSE_KEY` no Secret) | `1.0` | `0.2` |

```bash
ENV=hml   # ou prod
ECR=$(aws ssm get-parameter --name /oficina/$ENV/ecr_repository_url --query Parameter.Value --output text)
(cd k8s/overlays/$ENV && kustomize edit set image CHANGE_ME_ECR_REPOSITORY_URL=$ECR:<tag>)
kubectl apply -k k8s/overlays/$ENV
```

Pré-visualizar sem aplicar: `kubectl kustomize k8s/overlays/hml`. O kustomize **não** gera o Secret (só há o
`secret.example.yaml`, fora dos `resources`): crie os Secrets dos passos 2 a 4 **antes** do `apply -k`.

O egresso da `NetworkPolicy` inclui TCP 443 (exportador OTLP para o New Relic), além de DNS, PostgreSQL e SMTP.

## 6. Validar

```bash
kubectl get pods,hpa,svc,ingress -n oficina
kubectl -n oficina rollout status deployment/oficina-app
```

Pelo Ingress, de fora do cluster (a porta 80 é liberada apenas para o `allowed_cidr`):

```bash
curl "$(terraform -chdir=../infra output -raw application_url)/q/health/live"
```

Antes de demonstrar o autoscaling, confirme que o metrics-server está respondendo — sem ele o HPA fica
com a métrica em `<unknown>` e nunca escala:

```bash
kubectl top pods -n oficina
kubectl get hpa -n oficina
```

## 7. Teste de carga (material do vídeo demonstrativo)

Roda **de dentro do cluster**: o túnel do `kubectl port-forward` é um único fluxo TCP e vira gargalo do
lado cliente antes de gerar CPU suficiente nos pods, mascarando o teste.

```bash
kubectl run load-test -n oficina --rm -it --restart=Never --image=williamyeh/hey -- \
  -z 60s -c 50 http://oficina-service.oficina.svc.cluster.local/public/work-orders/OS-000001/status
```

Em outro terminal, acompanhar o HPA:

```bash
kubectl get hpa -n oficina -w
```

> O HPA escala **apenas por CPU**, deliberadamente. Escalar por memória com a JVM trava o Deployment em
> `maxReplicas`: o heap cresce até o limite e o coletor devolve pouca memória ao sistema operacional, então
> a métrica sobe e nunca desce. O `behavior` configurado sobe rápido (dobra a cada 30s) e desce devagar
> (janela de 180s, 1 pod por vez) para não derrubar pods no meio de requisições. Justificativa completa em
> [`../docs/spec-kubernetes.md`](../docs/spec-kubernetes.md).

## Endurecimento aplicado

- `runAsNonRoot` + `runAsUser: 1001` (a imagem declara `USER 1001` numérico — com nome de usuário o
  kubelet não consegue validar e o pod não sobe), `readOnlyRootFilesystem`,
  `allowPrivilegeEscalation: false`, `capabilities: drop [ALL]`, `seccompProfile: RuntimeDefault`
- ServiceAccount dedicada com `automountServiceAccountToken: false` — a aplicação não fala com a API do
  Kubernetes, então não precisa de credencial do cluster dentro do pod
- `/tmp` em `emptyDir`, porque a JVM precisa de escrita e a raiz é somente leitura
- `startupProbe` cobrindo o boot (pool de conexões + Flyway), o que permite um `livenessProbe` de período
  curto sem `initialDelay` inflado
