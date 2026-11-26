# Fase 4 — Visão geral e plano de execução

> Fonte: `12SOAT - Fase 4 - Tech challenge.pdf` (na raiz do repositório). Plano **proposto**; as decisões abertas (§5)
> precisam de confirmação antes da F0. Os ADRs 0018–0022 estão em [`docs/adr`](../../docs/adr/README.md) com status **Proposto**.
> **Especificações por requisito:** índice no §7 (specs 01–14, nesta pasta).

## 0. Estado (atualizar a cada sessão)

- **2026-11-23 — implementação começou.** O estado detalhado por spec passou para [`PROGRESSO.md`](PROGRESSO.md) (checklist, o que
  não pôde ser testado sem AWS/credenciais e próximo passo). OS Service: contratos, V7, Saga orquestrada, outbox/inbox e histórico
  prontos e testados (branch `feature/f4-os-saga`). Os itens abaixo são o registro do planejamento.

- **2026-11-22 — especificações escritas (specs 01–14 + ADR 0022); NADA implementado.** Falta apenas confirmar as decisões da §5
  e promover os documentos (ainda **sem commit**, arquivos novos em `oficina-app`, branch `homolog`). Os requisitos do PDF foram
  mapeados item a item no §7; o texto do PDF foi lido por inteiro (14 requisitos, 3 grupos de entregáveis).
- **Próximo passo (F0):** (1) criar branch `feature/f4-especificacoes` a partir de `homolog` e abrir PR com estes arquivos
  (o usuário faz o merge); (2) obter do usuário as respostas da §5 (D1–D4); (3) com elas, mudar os ADRs 0018–0022 para *Aceito*
  e marcar o ADR 0008 como substituído parcialmente pelo 0020; (4) criar `specs/fase4/PROGRESSO.md` (checklist por etapa) e
  só então seguir para F1. **O usuário fornecerá, quando chegarmos lá:** credenciais da Stripe (ativar Pix na conta de teste, chave de API de teste, segredo do webhook; antes: Mercado Pago, abandonado em 2026-11-26) (conta/aplicação/usuários de
  teste), organização e token do SonarCloud, criação dos repositórios, secrets e o vídeo.
- **Não decidido ainda:** tudo da §5 (D1–D4). As decisões da §2 são **recomendações** do Claude, não confirmadas pelo usuário.
- **Verificações pendentes** (apontadas nas specs; resolver na etapa indicada): Stripe S1–S7 ([05](05-billing-service-mercado-pago.md) §4.3, F4);
  Q1 sem conector SmallRye p/ SQS e Q2 versão do `quarkus-amazon-sqs` para Quarkus 3.15.7 ([03](03-mensageria-e-contratos.md) §5, spike da F2);
  `t3.large` permitido no Academy e capacidade do nó ([12](12-infra-k8s-mensageria.md) §3); `NetworkPolicy` efetiva no k3s p/ o IMDS
  ([12](12-infra-k8s-mensageria.md) §6, [ADR 0022](../../docs/adr/0022-credenciais-aws-dos-pods-via-imds.md)); DLQ FIFO exige fila FIFO ([03](03-mensageria-e-contratos.md) §1).
- **Achado desta rodada que muda o plano:** o IMDS do nó tem `hop_limit = 1` de propósito (pods não leem o `LabRole`); os serviços novos
  precisam de SQS/DynamoDB ⇒ ADR 0022 (hop limit 2 + `NetworkPolicy`).
- **Dívida da Fase 3 que a Fase 4 herda** (ver `specs/PROGRESSO.md`): New Relic sem dados validados, HPA sob carga, rollback do deploy,
  `soat-architecture`, reverter seed de `prod`. Juntar na primeira sessão AWS da Fase 4.

## 1. Requisitos obrigatórios (PDF)

| # | Requisito | Situação inicial |
|---|---|---|
| R1 | ≥ 3 microsserviços, cada um com repo, infra e banco próprios (OS, Billing, Execução) | só o monólito `oficina-app` |
| R2 | ≥ 1 banco SQL e ≥ 1 NoSQL no conjunto | só PostgreSQL |
| R3 | Billing: orçamento, pagamento via **Stripe** (Pix, modo de teste; substituiu o Mercado Pago), atualização da OS | inexistente |
| R4 | REST síncrono (quando necessário) + mensageria assíncrona | só REST |
| R5 | Nenhum serviço acessa o banco de outro | — |
| R6 | **Saga Pattern** com rollback/compensação em qualquer etapa | inexistente |
| R7 | Escolha da Saga documentada e justificada no README | pendente |
| R8 | Testes unitários em todos os serviços | existe na app (429) |
| R9 | ≥ 1 fluxo completo em BDD | inexistente |
| R10 | Cobertura ≥ 80% **por serviço** | gate só em pacotes críticos |
| R11 | SonarQube ou similar no CI | inexistente |
| R12 | Pipeline independente por serviço: build, testes, qualidade, deploy em K8s | padrão da Fase 3 |
| R13 | `main` protegida (PR + checks) | padrão da Fase 3 |
| R14 | Mensageria no K8s e observabilidade da Fase 3 | New Relic sem dados validados |

**Entregáveis**
- Repos: código, Dockerfile, manifestos K8s, pipelines, evidência de cobertura no README, documentação da arquitetura do serviço, Swagger/Postman.
- Vídeo ≤ 15 min: fluxo completo da OS; Saga e falhas; deploy automatizado com testes; monitoramento e rastreamento distribuído.
- PDF: participantes; links dos repos e do vídeo; diagrama geral; estratégia da Saga; justificativa da divisão e das tecnologias.

## 2. Decisões propostas (ADRs 0018–0022)

| Decisão | Proposta |
|---|---|
| Divisão e bancos | 3 serviços: OS (`oficina-app` refatorado, PostgreSQL), Billing (PostgreSQL próprio), Execução (DynamoDB) |
| Saga | **Orquestrada**, orquestrador no OS Service; outbox + consumidores idempotentes |
| Mensageria | **SQS FIFO** (`MessageGroupId = workOrderId`) com DLQ; substitui parcialmente o ADR 0008 |
| Pagamento | Porta `PaymentGateway`; adapter Stripe (Pix, modo de teste); fake nos testes |
| Qualidade | SonarCloud (repos públicos) + JaCoCo ≥ 80% do bundle inteiro, gate bloqueante |

## 3. Saga (OS → orçamento → aprovação → pagamento → execução)

| Passo | Sucesso | Falha → compensação |
|---|---|---|
| 1. OS aberta | `RECEIVED` | — |
| 2. Diagnóstico concluído → Billing gera/envia orçamento | `AWAITING_APPROVAL` | OS volta a `IN_DIAGNOSIS` |
| 3. Cliente aprova | segue ao pagamento | recusa/expiração: orçamento anulado, OS `CANCELLED` |
| 4. Pagamento na Stripe | novo status `AWAITING_PAYMENT` → pago | recusado/expirado: retentativas limitadas, depois orçamento anulado e OS `CANCELLED` |
| 5. Execução enfileirada e realizada | `IN_EXECUTION` → `FINISHED` | falha: **estorno na Stripe**, depois OS `CANCELLED` |
| Estorno falha | — | `COMPENSATION_PENDING` + alerta + DLQ (intervenção manual) |

Timeouts por passo; estado em `saga_instance` (log de passos). Envelope de mensagem: `messageId`, `sagaId`, `correlationId`,
`type`, `version`, `occurredAt`, `traceparent`.

## 4. Etapas

| Etapa | Entrega | Critério de pronto |
|---|---|---|
| F0 Preparação (sem AWS) | specs e ADRs, nomes, 2 repos novos com rulesets/Environments, `CLAUDE.md`/`PROGRESSO` da Fase 4 | repos protegidos; ADRs revisados |
| F1 Contratos | envelope, catálogo de comandos/eventos (AsyncAPI/JSON Schema), OpenAPI por serviço | contract tests nos dois lados |
| F2 Infra | Terraform: SQS+DLQ, DynamoDB, RDS do Billing; rotas no Ingress e no gateway (webhook Stripe sem JWT, com validação de assinatura); **spike de capacidade do nó** | `apply` em hml e memória medida com 3 serviços |
| F3 Execution Service | esqueleto: hexagonal, consumidor SQS idempotente, DynamoDB, pipeline | no ar em hml, consumindo e respondendo |
| F4 Billing Service | orçamento, aprovação, adapter Stripe, webhook, estorno, `Idempotency-Key` | pagamento sandbox ponta a ponta |
| F5 OS Service | orquestrador, outbox, migration V7 (`AWAITING_PAYMENT`, `saga_instance`, `outbox`), timeouts; API pública da Fase 3 compatível | suíte da Fase 3 verde + testes da Saga |
| F6 Qualidade (transversal, gate desde a F3) | Cucumber (feliz **e** compensação), JaCoCo ≥ 80% do bundle, Sonar bloqueante | CI reprova abaixo do limite |
| F7 CI/CD | pipeline por serviço: build, testes, Sonar, imagem, deploy por SSM, smoke, rollback | deploy independente de cada serviço |
| F8 Observabilidade | `traceparent`/`correlationId` nas mensagens; métricas da Saga; alertas de DLQ e `COMPENSATION_PENDING`; **fecha a dívida do E7** | trace único atravessa os 3 serviços; alertas disparados de propósito |
| F9 Sessão AWS | fluxo feliz, pagamento recusado, falha de execução com estorno, pod morto no meio da Saga, mensagem duplicada, HPA, rollback | checklist assinado; `destroy` conferido |
| F10 Entrega | READMEs (Saga, cobertura), diagramas, Postman, roteiro do vídeo, PDF | conferido contra §1 |

Caminho crítico: F0 → F1 → F2 → F3 → F4 → F5 → F9. Só F2, F8 e F9 exigem AWS; o resto roda local (Docker + LocalStack/ElasticMQ).

## 5. Decisões abertas (do usuário)

1. **D1** — Prazo de entrega e divisão do trabalho no grupo (define o corte de escopo, ver §8).
2. **D2** — SQS (recomendado) ou RabbitMQ; aceitar subir o tamanho do nó (`t3.large`). Specs 03 e 12.
3. **D3** — Billing com RDS próprio (recomendado) ou *database* separado na instância atual. Specs 02 e 12.
4. **D4** — Nome dos repos novos (`oficina-billing-service`, `oficina-execution-service`) e se `oficina-app` mantém o nome. Spec 01.
5. Do usuário, por regra do projeto: credenciais de teste da Stripe (chave de API e segredo do webhook; Pix ativado na conta), organização e token do SonarCloud, valores de secrets, criação dos repositórios, vídeo e PDF.

## 6. Riscos

1. **Capacidade do nó:** 6 agentes + 1 app já em 65% de memória; 3 JVMs exigem t3.large ou heap reduzido e `minReplicas: 1`. Spike na F2, antes do código.
2. **Orçamento do Academy:** +1 RDS e +1 sessão de validação; SQS/DynamoDB desprezíveis.
3. **Modo de teste da Stripe:** conta com Pix ativo e credenciais do usuário; na CI a Stripe fica atrás do fake.
4. **Dívida da Fase 3** (New Relic sem dados, HPA, rollback): juntar na primeira sessão AWS.
5. **Atomicidade e duplicidade de mensagens:** outbox e idempotência desde o primeiro serviço.

## 7. Índice das especificações e rastreabilidade (requisito → spec)

| Spec | Tema | Requisitos do PDF que atende |
|---|---|---|
| [01](01-microsservicos-e-fronteiras.md) | Microsserviços, fronteiras, propriedade de dados | R1, R5 |
| [02](02-persistencia-sql-nosql.md) | PostgreSQL × DynamoDB, schemas, V7 | R2 (e "banco próprio" de R1) |
| [03](03-mensageria-e-contratos.md) | SQS, envelope, catálogo de mensagens, outbox/inbox | R4 |
| [04](04-saga-orquestrada.md) | Saga orquestrada, compensações, cenários | R6, R7 |
| [05](05-billing-service-mercado-pago.md) | Orçamento, pagamento, webhook, estorno | R1 (Billing), R3 |
| [06](06-execution-service.md) | Fila de execução, diagnóstico e reparo | R1 (Execução), R2 (NoSQL) |
| [07](07-os-service-refatoracao.md) | Refatoração do `oficina-app` (OS) | R1 (OS: abertura, status, histórico) |
| [08](08-testes-e-cobertura.md) | Testes unitários, gate de 80% por serviço | R8, R10 |
| [09](09-bdd.md) | BDD do fluxo completo (Cucumber) | R9 |
| [10](10-qualidade-sonar.md) | SonarCloud como quality gate | R11 |
| [11](11-cicd-e-repositorios.md) | Pipelines independentes, rulesets, deploy | R12, R13 |
| [12](12-infra-k8s-mensageria.md) | Terraform, nó, filas, gateway, credenciais dos pods | R14 (mensageria no K8s), infra de R1/R12 |
| [13](13-observabilidade-distribuida.md) | Traces pela fila, métricas, dashboards, alertas | R14 (observabilidade), vídeo |
| [14](14-documentacao-e-entregaveis.md) | READMEs, diagramas, Postman/Swagger, PDF, checklist final | todos os entregáveis |

Cobertura dos 14 requisitos: R1→01,05,06,07 · R2→02,06 · R3→05 · R4→03 · R5→01,02 · R6→04 · R7→04,14 · R8→08 · R9→09 · R10→08,10 · R11→10 · R12→11 · R13→11 · R14→12,13.
Ordem de implementação e critérios por etapa: §4. Cada spec tem os seus **critérios de aceite**; o PROGRESSO da Fase 4 (criado na F0) os transforma em checklist.

## 8. Corte de escopo se o prazo apertar (ordem de sacrifício, do primeiro a cortar)

1. Dashboards extras e alertas além de DLQ/`COMPENSATION_PENDING`/falha de pagamento (spec 13 §4–5).
2. Cenários BDD além de S1, S2, S5, S6, S7 e testes S8–S11 além dos de unidade (specs 04 §6 e 09).
3. Fase `DIAGNOSIS` do Execution Service (spec 06) — fica só o REPAIR.
4. Reconciliação por *job* do Billing (spec 05 §5) — o webhook basta para a demo.
5. RFC de Saga (spec 14 §5) — o ADR 0019 cobre.

**Inegociável** (o PDF exige): 3 serviços com banco próprio (SQL + NoSQL), mensageria, Saga com compensação, testes + BDD + 80% + Sonar,
pipelines independentes com `main` protegida, observabilidade, README/Postman/diagrama/PDF/vídeo.
