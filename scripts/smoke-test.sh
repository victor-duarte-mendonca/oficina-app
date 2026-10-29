#!/usr/bin/env bash
# Smoke test da aplicação implantada (spec 08 §6). Roda no runner contra um port-forward do Service
# (o security group só libera a porta 80 para o IP do aluno e o runner tem IP dinâmico).
#
# Prova, nesta ordem:
#   1. liveness e readiness (readiness inclui o banco: a aplicação conectou e migrou o schema — V5/V6)
#   2. toda resposta traz X-Correlation-Id (E4)
#   3. /q/metrics expõe as métricas do Micrometer
#   4. rota protegida sem token -> 401
#   5. com a senha do admin semeado: login -> token; com APP_GATEWAY_KEY, token sem a chave -> 403 (GatewayKeyFilter);
#      token + chave -> 200; /me com token de staff -> 403
#
# Variáveis: BASE_URL (obrigatória), GATEWAY_KEY, ADMIN_USER (admin), ADMIN_PASSWORD (vazia = pula o login).
set -euo pipefail

BASE_URL="${BASE_URL:?BASE_URL obrigatória (ex.: http://localhost:18080)}"
GATEWAY_KEY="${GATEWAY_KEY:-}"
ADMIN_USER="${ADMIN_USER:-admin}"
ADMIN_PASSWORD="${ADMIN_PASSWORD:-}"

fail() {
  echo "FALHOU: $*" >&2
  exit 1
}

# Só o status HTTP; o corpo nunca é impresso (pode conter token).
status() {
  curl -sS -o /dev/null -w '%{http_code}' --max-time 10 "$@"
}

expect_status() {
  local description="$1" expected="$2"
  shift 2
  local got
  got="$(status "$@")"
  [ "$got" = "$expected" ] || fail "$description: esperado $expected, veio $got"
  echo "ok  $description -> $got"
}

# ===== 1. saúde =====
# A aplicação pode levar alguns segundos depois do rollout (pool + Flyway): espera ativa curta.
for _ in $(seq 1 20); do
  [ "$(status "$BASE_URL/q/health/ready" || true)" = "200" ] && break
  sleep 3
done
expect_status "readiness (banco conectado e schema migrado)" 200 "$BASE_URL/q/health/ready"
expect_status "liveness" 200 "$BASE_URL/q/health/live"

# ===== 2. correlação =====
headers="$(curl -sS -D - -o /dev/null --max-time 10 "$BASE_URL/q/health/live")"
echo "$headers" | grep -qi '^x-correlation-id: ' || fail "resposta sem X-Correlation-Id"
echo "ok  X-Correlation-Id presente"

# ===== 3. métricas =====
metrics="$(curl -sS --max-time 10 "$BASE_URL/q/metrics")"
echo "$metrics" | grep -q '^jvm_memory_used_bytes' || fail "/q/metrics sem métricas do Micrometer"
echo "ok  /q/metrics expõe as métricas do Micrometer"

# ===== 4. rota protegida sem credencial =====
# Sem token a resposta é 401 (a autorização por papel roda antes dos filtros JAX-RS), com ou sem chave.
expect_status "/admin sem token" 401 "$BASE_URL/admin/clients"
expect_status "/me sem token" 401 "$BASE_URL/me"

# ===== 5. login, rota protegida e filtro do gateway =====
if [ -n "$ADMIN_PASSWORD" ]; then
  gateway_header=()
  if [ -n "$GATEWAY_KEY" ]; then gateway_header=(-H "X-Gateway-Key: $GATEWAY_KEY"); fi

  token="$(curl -sS --max-time 10 -X POST -H 'Content-Type: application/json' \
    -d "$(jq -cn --arg u "$ADMIN_USER" --arg p "$ADMIN_PASSWORD" '{username: $u, password: $p}')" \
    "$BASE_URL/auth/login" | jq -r '.token // empty')"
  [ -n "$token" ] || fail "login do admin semeado não devolveu token"
  echo "ok  login do admin semeado -> token"

  if [ -n "$GATEWAY_KEY" ]; then
    # O GatewayKeyFilter barra quem JÁ autenticou mas não veio pelo gateway: token válido sem a chave -> 403.
    expect_status "token de ADMIN sem X-Gateway-Key" 403 -H "Authorization: Bearer $token" "$BASE_URL/admin/clients"
    expect_status "token de ADMIN com chave errada" 403 -H "X-Gateway-Key: chave-errada" \
      -H "Authorization: Bearer $token" "$BASE_URL/admin/clients"
  else
    echo "aviso: APP_GATEWAY_KEY vazia — filtro do gateway desligado, verificação pulada"
  fi

  expect_status "rota protegida com token de ADMIN" 200 ${gateway_header[@]+"${gateway_header[@]}"} \
    -H "Authorization: Bearer $token" "$BASE_URL/admin/clients"
  expect_status "/me com token de staff (papel errado)" 403 ${gateway_header[@]+"${gateway_header[@]}"} \
    -H "Authorization: Bearer $token" "$BASE_URL/me"
else
  echo "aviso: sem senha do admin semeado (seed desligado) — login e filtro do gateway não verificáveis (exigem token)"
fi

echo "Smoke test concluído."
