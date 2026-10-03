# Build Convention

Build Convention is a Gradle plugin for Java and Spring projects. It runs architecture, code quality, testing, coverage, and database migration checks as part of Gradle's `check` task.

## Architecture

The plugin enforces a practical hexagonal architecture with this dependency direction:

```text
Domain <- Application <- Adapter
```

Dependencies point inward. Domain contains business rules and does not depend on Spring, JPA, Lombok, Application, or Adapter code. Application coordinates use cases and owns the ports it needs. Adapters connect those ports to web, persistence, and other external technologies.

```text
com.example.project/
├── global/
│   ├── configuration/properties/     # shared @ConfigurationProperties
│   ├── exception/             # shared, technology-independent error contracts
│   ├── persistence/           # shared persistence configuration (JPA auditing, base entities)
│   └── web/                   # shared web and MVC configuration
└── {context}/                 # one bounded context
    ├── domain/
    │   ├── model/
    │   ├── vo/                # Value Objects and First-class Collections
    │   ├── event/
    │   └── exception/
    ├── application/
    │   ├── port/in/           # usecase/, command/, query/, result/
    │   ├── port/out/          # ports required by the use cases
    │   ├── service/
    │   └── exception/
    └── adapter/
        ├── in/web/            # Controller, request/, response/
        └── out/persistence/   # repository adapters, JPA entities, and mappers
```

### Request and persistence flow

```text
HTTP Request
    │
    ▼
Web Adapter (Controller)
    │ parses request and creates a UseCase command/query
    ▼
Inbound UseCase
    │ implemented by one Application Service
    ├──────────────► Domain model (business rules and state transitions)
    │
    └──────────────► Outbound Port
                         ▲
                         │ implemented by
                    Persistence Adapter
                         │ maps between Domain and JPA Entity
                         ▼
                      Database

Application result ──► Web Adapter ──► HTTP Response
```

The controller calls an inbound `*UseCase`; it does not depend on an Application Service implementation. An Application Service implements one use case and depends only on outbound `*Port` interfaces or stateless Domain Services. The persistence adapter implements repository ports and maps between domain objects and JPA entities.

## Domain and application conventions

- Aggregates and domain entities are immutable `final` classes. Value Objects and First-class Collections are `record` types.
- Domain identifiers use `{Domain}Id` Value Objects. Domain models do not expose database technical IDs.
- Create new aggregates with `create`; restore persisted state with `restore`. State-changing methods return a new aggregate.
- Aggregates refer to other aggregates by identifier, not by object reference. JPA relationships and lazy loading are not used to navigate between aggregates.
- Application services place transactions on public methods; read methods use read-only transactions. They do not perform external I/O inside a transaction.
- Repository ports distinguish creation from updates with `create` and `update`; they do not use ambiguous `save` or `upsert` contracts.
- Domain and Application exceptions use technology-independent error contracts. The Web layer maps business errors to HTTP `ProblemDetail` responses.

## What the plugin checks

- **Layer and context boundaries:** dependency direction, package placement, allowed imports, and references between bounded contexts, and a ban on `global` depending on any bounded context.
- **Application and web adapters:** use case and port collaborators, service transactions, controller dependencies, Spring component registration, and mapper responsibilities.
- **Domain models and persistence:** immutable aggregate and Value Object patterns, factory methods, identifier rules, domain/entity separation, JPA entity naming and table declarations, and repository port contracts.
- **Error handling:** exception structure, shared error contracts, and separation of HTTP/Spring types from Domain, Application, and outbound ports.
- **Code quality:** Checkstyle, PMD, and SpotBugs, including dedicated quality tasks for Domain code.
- **Tests and coverage:** Korean `@DisplayName`, given/when/then test structure, and observable assertions. The plugin also checks overall and changed-code coverage. The plugin applies PIT itself and `check` enforces mutation score 80 / test strength 85 on domain and application classes.
- **Database migrations:** identifier uniqueness, indexes and foreign keys on reference columns, and prohibited technical ID references.
- **Validation integrity:** attempts to disable required validation tasks, exclude their source, or ignore quality tool failures.

These checks verify structural rules that can be evaluated automatically. They do not prove that a domain model captures every business invariant or that a design is semantically correct.

## Java version

Projects that do not declare a Java version compile with Java 21. A project that declares Java 21 or newer (for example `java { toolchain { languageVersion = JavaLanguageVersion.of(25) } }`) keeps its own version; anything below 21 fails `validateJavaVersionConvention`. Architecture validation fails instead of silently skipping classes when the bundled bytecode reader cannot read them.

## Validation report

The `check` task writes a machine-readable report to `build/reports/build-convention/report.json`. The report includes the overall status and check results, with coverage and mutation information when available.
