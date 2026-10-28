# ADR 0001 — Arquitetura Hexagonal como estratégia de isolamento do domínio

- **Status:** Aceito
- **Origem:** Fase 2 (formalizada aqui); contrato em [`docs/spec-hexagonal.md`](../spec-hexagonal.md)

## Contexto

A aplicação concentra regras de negócio não triviais (máquina de estados da Ordem de Serviço, débito de estoque com
lock otimista, aprovação por token) e depende de infraestrutura que muda por motivos diferentes: banco, e-mail, métricas,
autenticação. Na Fase 1 algumas entidades (`Client`, `Vehicle`, `Part`, `ServiceItem`) eram `@Entity` JPA dentro do
domínio: o domínio conhecia o framework de persistência e não podia ser testado sem ele.

## Decisão

Isolar o domínio com **Arquitetura Hexagonal** (portas e adaptadores), em quatro pacotes sob `br.com.oficina`:

- `domain` — modelos, value objects, eventos e exceções, **sem** imports de JPA, Panache, Jakarta REST ou Quarkus;
- `application` — casos de uso e serviços, DTOs, `ports/in` e `ports/out`, e a regra de posse (`OwnershipGuard`);
- `infrastructure` — adapters de saída (persistência JPA/Panache, e-mail, métricas Micrometer) e a entidade de persistência
  `XxxEntity`, separada do modelo de domínio e traduzida por um `XxxMapper` sem lógica de negócio;
- `interfaces` — adapters de entrada (REST, filtros, mapeamento de erros).

Portas de entrada (`ports/in`) só existem quando há regra de negócio não trivial (ex.: `CreateWorkOrderUseCase`); CRUD
simples é atendido por serviço direto, para não criar interface sem ganho. Nenhuma classe fora de
`infrastructure/persistence` e `infrastructure/adapters/out` importa uma `Entity`.

## Consequências

- (+) O domínio é testável sem container nem banco; as portas permitem trocar o adapter (e-mail, métricas) sem tocar o caso de uso.
- (+) A fronteira ficou verificável: a regra de posse do cliente (`/me`) recebe um `AuthenticatedCaller` explícito, e o domínio
  continua sem conhecer `JsonWebToken`.
- (−) Mais classes por agregado (entity, mapper, adapter, port): custo de navegação e de manutenção dos mapeamentos.
- (−) Disciplina de revisão: nada no compilador impede um `import` indevido além dos testes de arquitetura/revisão.

## Alternativas consideradas

- **Camadas tradicionais (controller → service → repository com entity no domínio):** menos código, mas acopla regra de
  negócio ao JPA e foi o que já tinha causado o refactor.
- **Apenas separar entity de domínio sem portas:** resolve o import, mas deixa os casos de uso dependentes de classes concretas
  de persistência e de e-mail.
