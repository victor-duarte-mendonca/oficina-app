# Deploy no k3s — Oficina Mecânica

Pré-requisito: `KUBECONFIG` apontando para o cluster k3s (ver [`../infra/README.md`](../infra/README.md),
seção "Buscar o kubeconfig").

## Manifestos

| Arquivo               | Recurso                                                       |
|-----------------------|---------------------------------------------------------------|
| `base/` + `overlays/{hml,prod}` | Kustomize: a base tem os valores de produção; cada overlay sobrescreve réplicas/HPA, seed e nome do ambiente |
| `namespace.yaml`      | Namespace `oficina`                                           |
| `configmap.yaml`      | Configuração não sensível (perfil, CORS, SMTP, seed)          |
| `secret.yaml`         | **Placeholders** — os valores reais vêm dos comandos abaixo   |
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

```bash
ENV=hml   # ou prod
ECR=$(aws ssm get-parameter --name /oficina/$ENV/ecr_repository_url --query Parameter.Value --output text)
(cd k8s/overlays/$ENV && kustomize edit set image CHANGE_ME_ECR_REPOSITORY_URL=$ECR:<tag>)
kubectl apply -k k8s/overlays/$ENV
```

Pré-visualizar sem aplicar: `kubectl kustomize k8s/overlays/hml`. O `secret.yaml` da base só tem
placeholders; os valores reais vêm do passo 4 (aplique o Secret real depois do `apply -k`, ou remova o
`secret.yaml` dos `resources` no ambiente que o gerencia por fora).

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
