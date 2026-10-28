# Arquitetura — Fase 3

> Documento de arquitetura dos quatro repositórios. Diagramas em Mermaid (renderizam no GitHub e versionam como texto).
> Decisões: [`adr/`](adr/README.md) · Discussões: [`rfc/`](rfc/) · Banco: [`erd.md`](erd.md) · Operação: [`RUNBOOK.md`](RUNBOOK.md).

## 1. Contexto e repositórios

| Repositório | Responsabilidade |
|---|---|
| [`oficina-app`](https://github.com/victor-duarte-mendonca/oficina-app) | Aplicação Quarkus (domínio hexagonal), migrations Flyway, manifestos Kubernetes, pipeline de deploy, ADRs/RFCs e specs |
| [`oficina-auth-lambda`](https://github.com/victor-duarte-mendonca/oficina-auth-lambda) | Função de autenticação por CPF, Lambda authorizer, API Gateway, forwarder de logs |
| [`oficina-infra-k8s`](https://github.com/victor-duarte-mendonca/oficina-infra-k8s) | VPC, EC2/k3s, ECR, security groups, agentes New Relic e `observability/` (dashboards, alertas) |
| [`oficina-infra-database`](https://github.com/victor-duarte-mendonca/oficina-infra-database) | RDS PostgreSQL 16, parameter group, security group |

## 2. Diagrama de componentes

Mostra nuvem, APIs, banco e monitoramento. A **fronteira da VPC** explica as decisões [0005](adr/0005-lambda-na-vpc-somente-leitura.md)
e [0012](adr/0012-segredos-em-variavel-de-ambiente-do-lambda.md): só o que está dentro dela alcança o RDS, e nada dentro dela alcança a internet.

```mermaid
flowchart LR
    CLI["Cliente / front"]

    subgraph AWS["AWS (us-east-1)"]
        GW["API Gateway HTTP API<br/>7 rotas, throttling, access log JSON"]
        AUTHZ["Lambda authorizer<br/>(fora da VPC, só chave pública)"]
        FWD["Lambda forwarder de logs<br/>(fora da VPC)"]
        CWL[("CloudWatch Logs")]
        SSM[["SSM Parameter Store<br/>contrato entre repositórios"]]
        ECR[("ECR<br/>imagem da app")]

        subgraph VPC["VPC (sem NAT)"]
            LAUTH["Lambda auth por CPF<br/>SnapStart, papel RO"]
            subgraph EC2["EC2 única - k3s"]
                TRF["Traefik (Ingress)"]
                APP["Pods oficina-app<br/>HPA 2 a 4 por CPU"]
                AGENTS["Agentes New Relic<br/>(nri-bundle)"]
            end
            RDS[("RDS PostgreSQL 16<br/>privado, TLS obrigatório")]
        end
    end

    NR[("New Relic<br/>APM, métricas, logs, dashboards, alertas")]

    CLI -->|"POST /auth/token"| GW
    CLI -->|"/me, /admin (Bearer)"| GW
    GW -->|"AWS_PROXY"| LAUTH
    GW -.->|"valida JWT (cache 300 s)"| AUTHZ
    GW -->|"HTTP_PROXY + X-Gateway-Key + X-Correlation-Id"| TRF
    TRF --> APP
    LAUTH -->|"SELECT 5 colunas"| RDS
    APP -->|"JDBC TLS"| RDS
    ECR -.->|"pull da imagem"| EC2

    GW -->|"access log"| CWL
    LAUTH -->|"logs JSON"| CWL
    AUTHZ -->|"logs JSON"| CWL
    CWL -->|"assinatura"| FWD
    FWD -->|"Log API"| NR
    APP -->|"OTLP (traces)"| NR
    APP -->|"/q/metrics"| AGENTS
    AGENTS -->|"métricas, logs, eventos do cluster"| NR

    SSM -.->|"vpc, subnets, EIP, ECR"| LAUTH
    SSM -.->|"endpoint do RDS"| APP
```

**O que atravessa a fronteira da VPC:** de entrada, apenas a invocação do gateway à função de auth e a porta 5432 do RDS (só das duas
origens permitidas: SG do cluster e SG da função). De saída, **nada**: a função de auth não tem internet, então seus logs saem pelo
CloudWatch e o forwarder, que está fora da VPC, é quem fala com o New Relic.

## 3. Sequência — autenticação por CPF e consumo de uma rota protegida

```mermaid
sequenceDiagram
    autonumber
    participant C as Cliente
    participant GW as API Gateway
    participant L as Lambda auth (VPC)
    participant DB as RDS PostgreSQL
    participant Z as Lambda authorizer
    participant A as oficina-app (k3s)

    C->>GW: POST /auth/token { cpf }
    GW->>L: invoke (AWS_PROXY)
    L->>L: normaliza e valida dígitos do CPF
    alt CPF inválido
        L-->>C: 400 invalid_request
    else CPF válido
        L->>DB: SELECT id, name, status FROM clients WHERE cpf_cnpj = ? AND status = 'ACTIVE'
        alt inexistente, INACTIVE ou BLOCKED
            L-->>C: 401 (resposta idêntica nos três casos)
        else ativo
            L->>L: assina JWT RS256 (mesma chave da aplicação), exp 1 h
            L-->>C: 200 { access_token, expires_in }
        end
    end

    C->>GW: GET /me/work-orders (Authorization: Bearer)
    GW->>Z: valida assinatura, iss, exp e groups (cache 300 s)
    alt sem header Authorization
        GW-->>C: 401 do gateway (não chega ao cluster)
    else token recusado
        GW-->>C: 403 do gateway (não chega ao cluster)
    else token válido
        GW->>A: proxy HTTP + X-Gateway-Key + X-Correlation-Id = requestId
        A->>A: valida X-Gateway-Key, valida JWT, papel CLIENT
        A->>DB: OSs do clientId do token (nunca do path)
        A-->>C: 200 [ ... ] com X-Correlation-Id
    end
```

## 4. Sequência — abertura e aprovação de uma ordem de serviço

Mostra o cliente autenticado, a validação de posse do veículo, a métrica de negócio, a notificação e o caminho de erro que alimenta o
alerta de falha no processamento de OS (R7).

```mermaid
sequenceDiagram
    autonumber
    participant C as Cliente (token CLIENT)
    participant A as oficina-app
    participant G as OwnershipGuard
    participant DB as RDS PostgreSQL
    participant M as Micrometer (/q/metrics)
    participant S as SMTP
    participant NR as New Relic

    C->>A: POST /me/work-orders { vehicleId, ... }
    A->>G: o veículo pertence ao clientId do token?
    alt veículo de outro cliente ou inexistente
        A-->>C: 404 (não 403: não revela que existe)
    else do próprio cliente
        A->>DB: INSERT work_orders (status RECEIVED, order_number)
        A->>M: oficina_work_orders_created_total{origin=client}++
        A-->>C: 201 Created /me/work-orders/{id}
    end

    Note over A: a equipe diagnostica e envia o orçamento (staff)
    A->>DB: status AWAITING_APPROVAL
    A->>M: oficina_work_order_status_transitions_total e duração no status anterior
    A->>S: e-mail de mudança de status
    alt SMTP falha
        A->>M: oficina_integration_failures_total{integration=smtp}++
        Note over A,DB: a transição é preservada (o e-mail não desfaz o status)
    end

    C->>A: POST /me/work-orders/{id}/approval { APPROVE }
    alt OS não está em AWAITING_APPROVAL
        A->>M: oficina_work_order_transition_errors_total{reason=invalid_transition}++
        A-->>C: 422
        M-->>NR: scrape pelo newrelic-prometheus-agent
        Note over NR: alerta crítico "Falha no processamento de OS"
    else válida
        A->>DB: baixa estoque (lock otimista em parts.version) e status IN_EXECUTION
        A-->>C: 200
    end
```

## 5. Modelo de dados (ER)

O diagrama e a explicação dos relacionamentos estão em [`erd.md`](erd.md); a justificativa do banco, em
[RFC 0002](rfc/0002-escolha-do-banco-de-dados.md).

## 6. Segurança em camadas

| Camada | Controle |
|---|---|
| Borda | throttling por rota; authorizer (JWT RS256); rotas internas (`/q/metrics`, `/swagger-ui`) não expostas; `X-Gateway-Key` e `X-Correlation-Id` sobrescritos |
| Rede | SG do cluster só aceita as faixas do API Gateway (porta 80) e o `/32` do aluno; API do k3s (6443) só por túnel SSM; RDS privado, TLS obrigatório; Lambda de auth sem internet |
| Aplicação | JWT revalidado; `@RolesAllowed`; posse do recurso (`OwnershipGuard`); `GatewayKeyFilter` em tempo constante |
| Dados | papel `oficina_auth_ro` somente-leitura em 5 colunas; `CHECK` de domínio; CPF mascarado em claims e logs |
| Segredos | GitHub Secrets por Environment; Secret do Kubernetes criado pela pipeline (nunca no kustomize); licença New Relic em SSM SecureString |

## 7. Limitações assumidas

- **Sem TLS entre gateway e cluster** (sem domínio): [ADR 0003](adr/0003-api-gateway-http-api.md).
- **Node único, sem HA:** [ADR 0002](adr/0002-k3s-em-ec2-unica.md).
- **Trace começa na aplicação:** o HTTP API não propaga contexto W3C; o gateway se liga por `X-Correlation-Id`
  ([ADR 0010](adr/0010-observabilidade-otlp-new-relic.md)).
- **Single-AZ, sem backup no RDS** (laboratório): [RFC 0002](rfc/0002-escolha-do-banco-de-dados.md).
