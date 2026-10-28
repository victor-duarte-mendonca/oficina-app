# ADR 0009 — HPA por CPU apenas, sem métrica de memória

- **Status:** Aceito
- **Origem:** Fase 2 ([`k8s/base/hpa.yaml`](../../k8s/base/hpa.yaml)), formalizada aqui

## Contexto

O PDF cita o uso de HPA como exemplo de decisão a registrar. A aplicação é uma JVM em um node único com memória limitada.

## Decisão

HPA `autoscaling/v2` com **uma única métrica: CPU a 70% de utilização média** dos requests. Produção 2→4 réplicas; homologação
1→2 (overlay). `behavior`:

- `scaleUp`: sem janela de estabilização, até dobrar a cada 30 s (reage rápido ao pico);
- `scaleDown`: 180 s de estabilização e **uma réplica por minuto** (evita *flapping* em carga intermitente e queda de pod no meio
  de requisições).

## Consequências

- (+) Escala e desescala previsíveis, que é o que a demonstração de elasticidade precisa mostrar.
- (+) Sem dependência de Prometheus Adapter ou métricas customizadas.
- (−) Pressão de memória real **não** dispara escala; só o `limit` de memória (OOMKill) a contém.
- (−) CPU é proxy de carga: um gargalo de I/O (pool de conexões do banco, SMTP) pode saturar sem subir CPU. O pool é limitado a
  8 conexões por pod (4 × 8 = 32, abaixo do teto de ~112 do `db.t4g.micro`).
- (−) O teto de 4 réplicas depende da memória do node (ADR 0002 e 0010): validar com `kubectl top` que o quarto pod não fica
  `Pending`; opções em ordem: `maxReplicas: 3`, request de memória menor, `t3.large`.

## Alternativas consideradas

- **Escalar por memória:** armadilha com JVM. O heap cresce até o limite e devolve pouca memória ao SO; a métrica do container sobe e
  não desce, o HPA dispara por uso normal de heap e **nunca faz scale-in**, travando em `maxReplicas`.
- **Métrica de aplicação via Prometheus Adapter** (`jvm_memory_used_bytes`, ou RPS/latência): o caminho correto se houver
  necessidade real; custa uma peça a mais e memória no node único.
- **KEDA:** excelente para filas/eventos, sem uso aqui (ADR 0008).
