# Spec 14 — Documentação e entregáveis da Fase 4

> Atende **todos os entregáveis** do PDF: repositórios (com evidências), vídeo, PDF do portal. Complementa [`09-documentacao-arquitetural.md`](../09-documentacao-arquitetural.md) (Fase 3).
> **Responsabilidade do usuário:** roteiro e gravação do vídeo (≤ 15 min, YouTube/Vimeo, público ou não listado), identificação dos participantes e montagem/envio do PDF no portal. O Claude fornece o **material de apoio** (checklist, diagramas, textos-base).

## 1. Matriz entregável × evidência

| Entregável do PDF | Onde está | Spec/fonte |
|---|---|---|
| Código-fonte (3 repos) | repositórios públicos | 01, 05–07 |
| Dockerfile e manifestos K8s | `Dockerfile`, `k8s/base`, `k8s/overlays/{hml,prod}` em cada repo | 11, 12 |
| Pipelines CI/CD | `.github/workflows` em cada repo | 11 |
| Evidências de cobertura | `docs/cobertura.md` + badge + artefato JaCoCo | 08 |
| Qualidade (Sonar) | badge + link do dashboard | 10 |
| Documentação da arquitetura do serviço | `README.md` + `docs/arquitetura.md` por repo | §2 |
| Swagger ou Postman atualizado | `docs/api/openapi.{yaml,json}` + `postman/` por serviço (+ coleção ponta a ponta no OS) | §3 |
| Escolha da Saga justificada no README | seção "Saga Pattern" do README do OS, espelhada nos demais | 04 §1 |
| Vídeo (≤ 15 min) | link no README e no PDF | §5 (usuário) |
| PDF do portal | montado pelo usuário | §4 |

## 2. README de cada serviço (estrutura mínima)

1. O que o serviço faz e o que **não** faz (fronteira) · 2. Diagrama de contexto (Mermaid) · 3. Banco e justificativa SQL/NoSQL ·
4. API (resumo + link do OpenAPI/Postman) · 5. Mensagens que consome/publica (tabela do catálogo) · 6. **Saga**: papel do serviço e
compensações (no OS: escolha e justificativa completas) · 7. Como rodar localmente (docker compose com LocalStack/PostgreSQL) ·
8. Testes, **cobertura e qualidade** (números e badges) · 9. CI/CD (checks, ambientes, rollback) · 10. Observabilidade (métricas, traces, painéis) ·
11. Segurança (RBAC, segredos, webhook) · 12. Decisões (links dos ADRs/RFC) · 13. Limitações conhecidas · 14. Links dos outros repositórios.

## 3. Contratos e coleções

- OpenAPI exportado por serviço no CI (`/q/openapi`), commitado/atualizado por *workflow* e conferido por teste de contrato.
- **Postman**: coleção por serviço + **coleção "Fluxo completo da OS"** (login → abrir OS → diagnóstico → enviar para aprovação → aprovar → consultar Pix → simular pagamento → iniciar/concluir execução → histórico → saga) com *scripts* que gravam variáveis; variante "Run" (sem pastas que dependam de acesso interno), como na Fase 3.
- Catálogo de mensagens em `contracts/` + tabela no README ([03](03-mensageria-e-contratos.md)).

## 4. Diagramas (Mermaid versionado em `docs/`; exportados para o PDF)

| Diagrama | Conteúdo |
|---|---|
| **Arquitetura final** (obrigatório no PDF) | clientes/funcionários → API Gateway (+ Lambdas) → Traefik → 3 serviços; bancos (RDS OS, RDS Billing, DynamoDB); SQS (filas e DLQs); Stripe; New Relic; ECR/CI |
| Contexto/containers (C4 nível 1–2) | um por serviço |
| **Sequência da Saga** | S1 (feliz) e S6 (falha de execução → estorno), mais S2 e S7 |
| Máquina de estados | `WorkOrderStatus` e `saga_instance.state` |
| Dados | ERD do OS (atualizado com V7) e do Billing; modelo de itens do DynamoDB |
| Implantação | nó k3s, pods, SGs, rede, fluxo do webhook |

## 5. Documentos de decisão

- **ADRs** 0018–0022 (aceitar/atualizar com as respostas D1–D4); ADR 0008 marcado "substituído parcialmente por 0020"; novos ADRs conforme surjam (Payments × Orders API, outbox/inbox, DynamoDB tabela única).
- **RFC** (apenas se justificar — decisão tomada no começo do ciclo): `docs/rfc/0004-saga-orquestrada-vs-coreografada.md` com o espaço de soluções (orquestração, coreografia, Step Functions), por ser a decisão mais pesada e o que o avaliador mais lê. Os demais assuntos ficam em ADR.

## 6. Material de apoio ao vídeo e ao PDF (Claude prepara; usuário grava/monta)

- **Roteiro-checklist** do vídeo mapeando os 4 itens do PDF para tempos (sugestão: 3 min fluxo completo, 4 min Saga + falhas, 3 min deploy com testes, 3 min observabilidade, 2 min arquitetura) — em `docs/ROTEIRO-VIDEO-FASE4.md`, **como rascunho**.
- **Roteiro de ensaio** com comandos prontos (`scripts/demo-fase4.sh`: abre OS, força cada falha S2/S6/S7 por configuração, imprime links dos dashboards).
- **Texto-base do PDF**: divisão e justificativa, escolha da Saga, tecnologias e porquês, tabela de cobertura, limitações — em `docs/PDF-FASE4.md`.
- Seed de demonstração com OS nos estados úteis; lembrar de **reverter o seed de prod** depois da gravação (pendência da Fase 3).

## 7. Checklist final de entrega (conferir contra o PDF, linha a linha)

- [ ] ≥ 3 microsserviços, repos/infra/banco próprios · [ ] SQL **e** NoSQL · [ ] Nenhum acesso cruzado a banco
- [ ] REST + mensageria · [ ] Saga com compensação, documentada no README
- [ ] Testes unitários nos 3 · [ ] ≥ 1 fluxo BDD · [ ] Cobertura ≥ 80% por serviço (evidência) · [ ] Sonar no CI
- [ ] Pipeline independente por serviço, deploy em K8s · [ ] `main` protegida
- [ ] Dockerfile + manifestos + pipelines + cobertura + arquitetura + Swagger/Postman em cada repo
- [ ] Vídeo ≤ 15 min com os 4 itens · [ ] PDF com participantes, links, vídeo, diagrama, Saga, justificativa
- [ ] Usuário `soat-architecture` com acesso aos repositórios (pendência da Fase 3, repete)

## 8. Fora de escopo

Redigir ou gravar o vídeo; preencher dados pessoais dos participantes; enviar o PDF ao portal.
