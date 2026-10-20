package com.dochiri.ilsim.runner.profile

internal enum class Category { FRAMEWORK, TESTING, ANALYSIS }

internal class Rule(
    val id: String,
    val label: String,
    val category: Category,
    val coords: List<Regex> = emptyList(),
    val plugins: List<Regex> = emptyList(),
)

private fun c(vararg r: String) = r.map { Regex(it) }

internal val RULES: List<Rule> = listOf(
    Rule("spring-boot", "Spring Boot", Category.FRAMEWORK, c("""org\.springframework\.boot:spring-boot-starter.*"""), c("""org\.springframework\.boot""")),
    Rule("spring-web-mvc", "Spring Web MVC", Category.FRAMEWORK, c("""org\.springframework\.boot:spring-boot-starter-(web|webmvc)""")),
    Rule("spring-webflux", "Spring WebFlux", Category.FRAMEWORK, c("""org\.springframework\.boot:spring-boot-starter-webflux""")),
    Rule("spring-data-jpa", "Spring Data JPA", Category.FRAMEWORK, c("""org\.springframework\.boot:spring-boot-starter-data-jpa""", """org\.springframework\.data:spring-data-jpa""")),
    Rule("spring-jdbc", "Spring JDBC", Category.FRAMEWORK, c("""org\.springframework\.boot:spring-boot-starter-jdbc""")),
    Rule("spring-security", "Spring Security", Category.FRAMEWORK, c("""org\.springframework\.boot:spring-boot-starter-(oauth2-.*|security)""")),
    Rule("spring-validation", "Bean Validation", Category.FRAMEWORK, c("""org\.springframework\.boot:spring-boot-starter-validation""")),
    Rule("spring-actuator", "Spring Actuator", Category.FRAMEWORK, c("""org\.springframework\.boot:spring-boot-starter-actuator""")),
    Rule("kafka", "Kafka", Category.FRAMEWORK, c("""org\.springframework\.boot:spring-boot-starter-kafka""", """org\.springframework\.kafka:spring-kafka""", """org\.apache\.kafka:kafka-clients""")),
    Rule("redis", "Redis", Category.FRAMEWORK, c("""org\.springframework\.boot:spring-boot-starter-data-redis.*""")),
    Rule("mongodb", "MongoDB", Category.FRAMEWORK, c("""org\.springframework\.boot:spring-boot-starter-data-mongodb.*""")),
    Rule("flyway", "Flyway", Category.FRAMEWORK, c("""org\.flywaydb:.*""", """org\.springframework\.boot:spring-boot-starter-flyway""")),
    Rule("liquibase", "Liquibase", Category.FRAMEWORK, c("""org\.liquibase:.*""")),
    Rule("querydsl", "QueryDSL", Category.FRAMEWORK, c("""com\.querydsl:.*"""), c("""com\.ewerk\.gradle\.plugins\.querydsl""")),
    Rule("mybatis", "MyBatis", Category.FRAMEWORK, c("""org\.mybatis.*:.*""")),
    Rule("jooq", "jOOQ", Category.FRAMEWORK, c("""org\.jooq:.*""")),
    Rule("lombok", "Lombok", Category.FRAMEWORK, c("""org\.projectlombok:lombok""")),
    Rule("mapstruct", "MapStruct", Category.FRAMEWORK, c("""org\.mapstruct:.*""")),
    Rule("resilience4j", "Resilience4j", Category.FRAMEWORK, c("""io\.github\.resilience4j:.*""")),
    Rule("jackson-kotlin", "Jackson Kotlin", Category.FRAMEWORK, c(""".*:jackson-module-kotlin""")),
    Rule("coroutines", "Kotlin Coroutines", Category.FRAMEWORK, c("""org\.jetbrains\.kotlinx:kotlinx-coroutines.*""")),
    Rule("springdoc", "springdoc-openapi", Category.FRAMEWORK, c("""org\.springdoc:.*""")),
    Rule("mysql", "MySQL 드라이버", Category.FRAMEWORK, c("""com\.mysql:mysql-connector-j""", """mysql:mysql-connector-java""")),
    Rule("postgresql", "PostgreSQL 드라이버", Category.FRAMEWORK, c("""org\.postgresql:postgresql""")),
    Rule("h2", "H2", Category.FRAMEWORK, c("""com\.h2database:h2""")),
    Rule("kotlin-spring-plugin", "Kotlin Spring 플러그인(all-open)", Category.FRAMEWORK, plugins = c("""org\.jetbrains\.kotlin\.plugin\.spring""")),
    Rule("kotlin-jpa-plugin", "Kotlin JPA 플러그인(no-arg)", Category.FRAMEWORK, plugins = c("""org\.jetbrains\.kotlin\.plugin\.jpa""")),

    Rule("junit5", "JUnit 5", Category.TESTING, c("""org\.junit\.jupiter:.*""", """org\.jetbrains\.kotlin:kotlin-test-junit5""", """org\.springframework\.boot:spring-boot-starter-test""")),
    Rule("spring-test", "Spring Boot Test", Category.TESTING, c("""org\.springframework\.boot:spring-boot-starter-test""", """org\.springframework\.boot:spring-boot-starter-.*-test""")),
    Rule("mockito", "Mockito", Category.TESTING, c("""org\.mockito:.*""")),
    Rule("mockk", "MockK", Category.TESTING, c("""io\.mockk:.*""")),
    Rule("assertj", "AssertJ", Category.TESTING, c("""org\.assertj:.*""")),
    Rule("kotest", "Kotest", Category.TESTING, c("""io\.kotest:.*""")),
    Rule("testcontainers", "Testcontainers", Category.TESTING, c("""org\.testcontainers:.*""")),
    Rule("wiremock", "WireMock", Category.TESTING, c(""".*wiremock.*:.*""")),
    Rule("rest-assured", "REST Assured", Category.TESTING, c("""io\.rest-assured:.*""")),

    Rule("archunit", "ArchUnit", Category.ANALYSIS, c("""com\.tngtech\.archunit:.*""")),
    Rule("detekt", "detekt", Category.ANALYSIS, c("""io\.gitlab\.arturbosch\.detekt:.*"""), c("""io\.gitlab\.arturbosch\.detekt""")),
    Rule("ktlint", "ktlint", Category.ANALYSIS, c("""com\.pinterest.*:ktlint.*"""), c("""org\.jlleitschuh\.gradle\.ktlint""")),
    Rule("spotless", "Spotless", Category.ANALYSIS, plugins = c("""com\.diffplug\.spotless""")),
    Rule("checkstyle", "Checkstyle", Category.ANALYSIS, c("""com\.puppycrawl\.tools:checkstyle"""), c("""checkstyle""")),
    Rule("pmd", "PMD", Category.ANALYSIS, c("""net\.sourceforge\.pmd:.*"""), c("""pmd""")),
    Rule("spotbugs", "SpotBugs", Category.ANALYSIS, c("""com\.github\.spotbugs:.*"""), c("""com\.github\.spotbugs""")),
    Rule("errorprone", "Error Prone", Category.ANALYSIS, c("""com\.google\.errorprone:.*"""), c("""net\.ltgt\.errorprone""")),
    Rule("jacoco", "JaCoCo", Category.ANALYSIS, plugins = c("""jacoco(-report-aggregation)?""")),
    Rule("pitest", "PIT(변이 테스트)", Category.ANALYSIS, c("""org\.pitest:.*"""), c(""".*pitest.*""")),
    Rule("sonarqube", "SonarQube", Category.ANALYSIS, plugins = c("""org\.sonarqube""")),
    Rule("dependency-check", "OWASP Dependency-Check", Category.ANALYSIS, plugins = c("""org\.owasp\.dependencycheck""")),
)
