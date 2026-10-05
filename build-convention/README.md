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
│   ├── configuration/         # @Configuration, properties/
│   ├── domain/                # shared kernel values (Money, Quantity)
│   ├── application/exception/ # shared application exceptions
│   ├── exception/             # GlobalApiControllerAdvice, ProblemDetails
│   └── persistence/           # shared persistence configuration (JPA auditing, base entities)
└── {context}/                 # one bounded context, or {group}/{module}/
    ├── domain/                # aggregate root may sit here directly
    │   ├── model/
    │   ├── vo/                # Value Objects and First-class Collections
    │   ├── event/             # domain events: past-tense records without an Event suffix
    │   └── exception/
    ├── application/
    │   ├── port/in/           # usecase/, command/, query/, result/
    │   ├── port/out/          # ports required by the use cases
    │   ├── service/
    │   └── exception/
    └── adapter/
        ├── in/web/            # Controller, {Context}ExceptionHandler, request/, response/
        └── out/persistence/   # repository adapters, JPA entities, and mappers
```

`docs/templates` contains the `book` context in `com.example.demo`. See [template execution requirements](docs/templates/README.md) for dependencies and the verified scope.

### Consumer guidance through `ai`

The published plugin JAR includes guidance, detailed convention documents, and reference template sources.
Before starting Claude or Codex, `ai` copies this bundle from the locally published plugin version used by its
convention check into the consumer project's `docs/build-convention/` and adds a managed block to `AGENTS.md`.
Both CLIs also receive the guidance path in the task prompt. Existing instructions outside the block are preserved;
template sources stay in documentation and are never copied into production source directories.
The entry point is [conventions/README.md](docs/conventions/README.md); detailed rules live in that directory.

`docs/build-convention/.ai-guidance.json` records the plugin version and file hashes. Unchanged files are not
rewritten. Locally edited or conflicting documents prevent that project's synchronization and produce a notice;
unrelated documents are preserved. Independent Gradle projects get separate bundles, while modules share their
Gradle root's guidance. A project applying its own plugin version must check it against the recorded guidance version.

Run the repository's `install.sh` again if an older local JAR has no guidance bundle. Missing guidance does not
prevent the AI task or its post-task checks. Requests disabling convention checks skip guidance deployment too.

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

- Aggregates and domain entities are immutable `final` classes. Value Objects and First-class Collections are `final` classes with private constructors and `of` factories; state enums live in `domain.vo`.
- Domain identifiers use `{Domain}Id` Value Objects wrapping UUID v7; constructors reject other UUID versions. Domain models do not expose database technical IDs.
- Create new aggregates with `create`; restore persisted state with `restore`. State-changing methods preserve the identifier and return a new aggregate.
- Domain collections use a single-field First-class Collection with defensive copying. Immutable Outbox records live in `global.domain.outbox` or context `domain.outbox`.
- Aggregates refer to other aggregates by identifier, not by object reference. JPA relationships and lazy loading are not used to navigate between aggregates.
- Application services place transactions on public methods; read methods use read-only transactions. They do not perform external I/O inside a transaction.
- Repository ports may use `save`, `create`, or `update`; they do not use `upsert`.
- Each context has `abstract sealed` Domain and Application exception bases with `final` subclasses. A per-context `{Context}ExceptionHandler` maps them to HTTP `ProblemDetail` responses.
- The reference layout is the `tool-test-lab` repository; structures outside it are rejected.

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
