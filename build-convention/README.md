# Build Convention

Build Convention provides Gradle plugins for Java/Spring conventions and Kotlin/JVM domain conventions. The Java plugin runs architecture, code quality, testing, coverage, and database migration checks as part of Gradle's `check` task. The Kotlin plugin checks domain sources and compiled architecture.

## Modules

The build is split by language so each language's rules and tooling stay separate.

| Directory | Artifact | Plugin ID | Contents |
| --- | --- | --- | --- |
| `core/` | `build-convention-core` | none | Language-neutral parts (no dependency on language modules): the `hexagonalConvention` extension, bounded context and technical dependency catalogs, rule IDs, and the ArchUnit, migration, and coverage class checks |
| `java/` | `build-convention-java` | `io.github.dochiri0916.build-convention.java` | Java source validators, Checkstyle/PMD/SpotBugs wiring, anti-bypass checks, and the report |
| `kotlin/` | `build-convention-kotlin` | `io.github.dochiri0916.build-convention.kotlin` | Kotlin PSI domain/VO/collection rules, common ArchUnit checks, anti-bypass, and reports |
| root | `build-convention` | `io.github.dochiri0916.build-convention` | The original ID, which applies the Java conventions |

Existing Java consumers keep using `io.github.dochiri0916.build-convention`. Kotlin/JVM consumers apply `io.github.dochiri0916.build-convention.kotlin`; mixed source modules can apply both language plugins. See [Kotlin domain conventions](docs/conventions/kotlin-domain-conventions.md) for their scope and the Kotlin templates. Mixed modules write `report.json` and `report-kotlin.json` separately.

## Usage

Build, verify, and publish locally from this directory:

```bash
./gradlew clean check publishToMavenLocal --no-daemon
```

Add the local plugin repository to the consumer project's `settings.gradle`:

```groovy
pluginManagement {
    repositories {
        mavenLocal()
        gradlePluginPortal()
        mavenCentral()
    }
}
```

Apply the plugin in the consumer project's `build.gradle`, then run its `./gradlew check`:

```groovy
plugins {
    id 'java'
    id 'io.github.dochiri0916.build-convention' version '1.2.0'
}
```

Kotlin/JVM consumers use `io.github.dochiri0916.build-convention.kotlin` with their Kotlin/JVM plugin. Consumer rules and examples are maintained in [conventions](docs/conventions/README.md) and [templates](docs/templates/README.md).

## Internal packages

Each module groups files under `io.github.dochiri0916.buildconvention` by their role and the rules they check. Tests use the same package as the implementation they exercise. Cross-rule regression tests belong to the architecture or domain group they verify.

| Package | Responsibility |
| --- | --- |
| `extension`, `catalog`, `context` | Shared configuration, rule/dependency catalogs, and bounded contexts |
| `plugin`, `integrity`, `report` | Gradle task wiring, prevention of disabled checks, and machine reports |
| `coverage` | Shared bytecode helpers for coverage inputs |
| `source.java`, `source.kotlin` | Source discovery, parsing, type indexes, and syntax inspection |
| `validator.architecture` | Architecture entry points, package topology, and ArchUnit |
| `validator.domain.aggregate`, `.vo`, `.collection`, `.factory` | Domain identity/state, value objects, immutable collections, and construction contracts |
| `validator.domain` | Domain-wide validation and integration tests |
| `validator.application` | Application contracts and transaction boundaries |
| `validator.adapter.web`, `.persistence` | Web conversion/errors and JPA/Querydsl adapter rules |
| `validator.exception`, `.messaging`, `.migration` | Exception hierarchies, messaging contracts, and database changes |
| `validator.quality`, `.review` | Code style/coverage checks and execution-evidence review |

The Gradle modules determine which language owns a class. `core` contains language-neutral parts; Java and Kotlin validators share responsibility package names and keep distinct class names. Plugin IDs, task names, and report rule IDs remain the consumer interface.

## Architecture and consumer conventions

The plugin enforces inward dependencies: `Domain <- Application <- Adapter`. The canonical package layout and request/persistence flow are in [package structure](docs/conventions/package-structure.md). Detailed domain and application rules are maintained in [consumer conventions](docs/conventions/README.md), including the [domain](docs/conventions/domain-conventions.md), [application](docs/conventions/application-conventions.md), [persistence](docs/conventions/persistence-conventions.md), and [exception](docs/conventions/exception-conventions.md) documents.

The reference layout is the `tool-test-lab` repository; structures outside it are rejected.

Reference sources and their dependencies and verified scope are in [templates](docs/templates/README.md).

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
