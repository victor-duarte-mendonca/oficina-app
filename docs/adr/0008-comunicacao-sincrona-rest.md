# ADR 0008 — Comunicação síncrona REST; sem mensageria nesta fase

- **Status:** Aceito
- **Origem:** [`specs/00-visao-geral.md`](../../specs/00-visao-geral.md) §3 e §7 (escolha do padrão de comunicação)

## Contexto

O sistema tem um domínio pequeno (uma oficina), uma aplicação e uma função de autenticação. O PDF cita a escolha do padrão de
comunicação como exemplo de decisão a registrar.

## Decisão

Toda a comunicação é **síncrona sobre HTTP/REST**: cliente → API Gateway → Lambda (invocação) ou aplicação (proxy HTTP), e
aplicação → RDS (JDBC). **Sem mensageria** (SQS/SNS/Kafka), sem CQRS, sem event sourcing e sem service mesh.

A única integração externa assíncrona em espírito, o e-mail de mudança de status, é chamada **na mesma requisição, mas isolada**:
uma falha de SMTP é contada (`oficina_integration_failures_total`) e logada, e **não** desfaz a transição de status, que é a
invariante de negócio.

## Consequências

- (+) Menos peças: depuração, rastreio e testes mais simples; sem fila, DLQ ou idempotência de consumidor para operar.
- (+) Consistência forte dentro de uma transação, que é o que o domínio precisa (ADR de banco, RFC 0002).
- (−) **Acoplamento temporal:** o e-mail e a latência do SMTP entram no caminho da requisição de transição.
- (−) Sem buffer para picos: a absorção depende do HPA e do throttling do gateway.
- (−) Um segundo consumidor de eventos de OS (BI, notificações em lote) exigiria publicar eventos depois.

## Alternativas consideradas

- **SQS/SNS para notificações:** desacoplaria o e-mail, mas adicionaria infraestrutura, custo e idempotência sem requisito que a
  justifique; o evento de domínio (`WorkOrderStatusChangedEvent`) já existe e é a costura para isso.
- **Mensageria entre aplicação e Lambda:** a autenticação é uma chamada de pedido-resposta; assíncrono não se aplica.

Quando outra abordagem seria melhor: múltiplos consumidores independentes das mudanças de OS, ou integração com SMTP lento ou
instável a ponto de afetar a latência. Aí vale um outbox + fila.
