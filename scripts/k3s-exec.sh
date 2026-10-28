#!/usr/bin/env bash
# Executa um comando no node k3s de um ambiente via SSM Run Command (E9). Sem SSH, sem chave privada da
# EC2 e sem expor a porta 6443: é o mesmo caminho que a pipeline de deploy usa para o kubeconfig.
#
# Uso:    scripts/k3s-exec.sh <hml|prod> "<comando>"
# Exemplo: scripts/k3s-exec.sh prod "kubectl top nodes"
#          scripts/k3s-exec.sh prod "kubectl get pods,hpa -A"
#
# Precisa de credenciais AWS no ambiente (sessão do Academy ativa). KUBECONFIG já vem configurado para o
# node (/etc/rancher/k3s/k3s.yaml). O comando roda como root na EC2: use só para leitura e diagnóstico.
set -euo pipefail

STACK_ENV="${1:?uso: $0 <hml|prod> \"<comando>\"}"
COMMAND="${2:?uso: $0 <hml|prod> \"<comando>\"}"
REGION="${AWS_REGION:-us-east-1}"

case "$STACK_ENV" in
  hml | prod) ;;
  *)
    echo "erro: ambiente deve ser hml ou prod (recebi '$STACK_ENV')" >&2
    exit 2
    ;;
esac

INSTANCE_ID="$(aws ec2 describe-instances --region "$REGION" \
  --filters "Name=tag:Project,Values=oficina" "Name=tag:Environment,Values=${STACK_ENV}" \
  "Name=instance-state-name,Values=running" \
  --query 'Reservations[0].Instances[0].InstanceId' --output text)"
if [ -z "$INSTANCE_ID" ] || [ "$INSTANCE_ID" = "None" ]; then
  echo "erro: nenhuma EC2 em execução com Project=oficina e Environment=${STACK_ENV}" >&2
  exit 1
fi

# O comando vai em base64: dispensa escapar aspas, $ e ; dentro do parâmetro do SSM.
PAYLOAD="$(printf 'export KUBECONFIG=/etc/rancher/k3s/k3s.yaml; %s' "$COMMAND" | base64 | tr -d '\n')"
COMMAND_ID="$(aws ssm send-command --region "$REGION" --instance-ids "$INSTANCE_ID" \
  --document-name AWS-RunShellScript \
  --parameters "commands=echo ${PAYLOAD} | base64 -d | bash" \
  --query 'Command.CommandId' --output text)"

STATUS="Pending"
for _ in $(seq 1 60); do
  STATUS="$(aws ssm get-command-invocation --region "$REGION" --command-id "$COMMAND_ID" \
    --instance-id "$INSTANCE_ID" --query 'Status' --output text 2> /dev/null || echo Pending)"
  case "$STATUS" in
    Success | Failed | Cancelled | TimedOut) break ;;
  esac
  sleep 2
done

aws ssm get-command-invocation --region "$REGION" --command-id "$COMMAND_ID" --instance-id "$INSTANCE_ID" \
  --query 'StandardOutputContent' --output text
STDERR="$(aws ssm get-command-invocation --region "$REGION" --command-id "$COMMAND_ID" --instance-id "$INSTANCE_ID" \
  --query 'StandardErrorContent' --output text)"
if [ -n "$STDERR" ] && [ "$STDERR" != "None" ]; then
  echo "$STDERR" >&2
fi

if [ "$STATUS" != "Success" ]; then
  echo "erro: comando terminou com status $STATUS" >&2
  exit 1
fi
