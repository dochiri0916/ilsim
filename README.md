# build-convention

`Checkstyle`, `PMD`, `SpotBugs`를 한 번에 적용하는 Gradle 컨벤션 플러그인 프로젝트입니다.

## Plugin ID

- `com.dochiri.lint-convention`

## 레이어별 컨벤션 문서

- [도메인 컨벤션](docs/conventions/domain.md)
- [애플리케이션 컨벤션](docs/conventions/application.md)
- [인프라스트럭처 컨벤션](docs/conventions/infrastructure.md)
- [프레젠테이션 컨벤션](docs/conventions/presentation.md)
- [아키텍처 컨벤션](docs/conventions/architecture.md)
- [테스트 컨벤션](docs/conventions/test.md)

## 정적 분석 도구 역할

- `Checkstyle`
  - 코드 스타일/포맷 규칙을 검사합니다.
  - 예: `*` import 금지, 중괄호 강제, 사용하지 않는 import 탐지

- `PMD`
  - 소스 코드를 정적으로 분석해 코드 품질/설계 이슈를 탐지합니다.
  - 예: 복잡도 과다, 과도하게 긴 메서드, 잠재적 안티패턴

- `SpotBugs`
  - 컴파일된 바이트코드를 분석해 런타임 버그 가능성을 탐지합니다.
  - 예: NPE 가능성, equals/hashCode 계약 위반, 자원 누수 패턴

## 컨벤션 코드 구조

- `com.dochiri.convention.plugin`: 플러그인 엔트리
- `com.dochiri.convention.extension`: 사용자 설정 확장
- `com.dochiri.convention.validator`: 아키텍처/엔티티 규칙 검증
- `com.dochiri.convention.support`: 소스 분석 공통 유틸

## 로컬에서 배포

```bash
./gradlew publishToMavenLocal
```

## 다른 프로젝트에서 사용

`settings.gradle`

```groovy
pluginManagement {
    repositories {
        mavenLocal()
        gradlePluginPortal()
        mavenCentral()
    }
}
```

`build.gradle`

```groovy
plugins {
    id 'java'
    id 'com.dochiri.lint-convention' version '1.0.0'
}
```

실행:

```bash
./gradlew check
```

## 현재 컨벤션과 검증 도구 (`./gradlew check` 기준)

- 도메인은 `application`, `infrastructure`, `presentation`에 의존하지 않는다.
  - 검증 도구: 플러그인 내장 `validateHexagonalArchitecture`

- 애플리케이션은 `infrastructure`, `presentation`에 의존하지 않는다.
  - 검증 도구: 플러그인 내장 `validateHexagonalArchitecture`

- 도메인 패키지에는 `@Entity`를 두지 않고, 도메인 모델과 영속 엔티티를 분리한다.
  - 검증 도구: 플러그인 내장 `validateEntityNamingConvention`

- 엔티티 클래스명은 단수형을 사용하고, 테이블명은 복수형을 사용한다.
  - 검증 도구: 플러그인 내장 `validateEntityNamingConvention`

- 도메인 클래스는 `private` 기본 생성자를 사용하고, 인자 생성자 대신 정적 팩토리 메서드를 사용한다.
  - 검증 도구: 플러그인 내장 `validateDomainStaticFactoryConvention`

- 도메인 코드는 프레임워크(Spring/JPA/QueryDSL)에 직접 의존하지 않는다.
  - 검증 도구: `checkstyleDomain` + 샘플의 `HexagonalArchitectureTest`(ArchUnit)

- 코드 스타일은 `*` import 금지, 중괄호 강제, 미사용 import 제거를 기본으로 한다.
  - 검증 도구: `checkstyleMain`, `checkstyleTest`, `checkstyleDomain`

- 도메인 로직은 복잡도를 낮게 유지한다.
  - 검증 도구: `pmdDomain` (`CyclomaticComplexity`, `NPathComplexity`)

- 잠재적인 런타임 버그(NPE 가능성 등)는 바이트코드 분석으로 점검한다.
  - 검증 도구: `spotbugsMain`, `spotbugsTest`, `spotbugsDomain`

- null 방어는 `requireNonNull(value)`를 사용한다. (메시지 미사용)
  - 검증 도구: 코드 리뷰/팀 컨벤션 (현재 자동 강제 규칙은 없음)

도메인 품질 게이트만 별도로 실행:

```bash
./gradlew checkstyleDomain pmdDomain spotbugsDomain
```

설정 커스터마이징 예시:

```groovy
hexagonalConvention {
    domainPackageSegment = 'domain'
    applicationPackageSegment = 'application'
    infrastructurePackageSegment = 'infrastructure'
    presentationPackageSegment = 'presentation'

    enforceDomainEntitySeparation = true
    enforceDomainStaticFactoryMethod = true
    requireTableAnnotation = true
    entitySingularNameExceptions = ['Status', 'Address']
    pluralTableNameExceptions = ['people']
    domainStaticFactoryExceptions = []
}
```

## 로컬 includeBuild로 사용 (배포 없이)

소비 프로젝트의 `settings.gradle`:

```groovy
pluginManagement {
    includeBuild("../build-convention")
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}
```

그 다음 소비 프로젝트 `build.gradle`:

```groovy
plugins {
    id 'java'
    id 'com.dochiri.lint-convention'
}
```

## 샘플 소비 프로젝트

이미 이 저장소 안에 샘플 소비 프로젝트가 있습니다.

- `samples/consumer-test`

샘플 포트 구조:

- `domain/user`, `domain/department`: 도메인 모델
- `application/user/port/in`, `application/department/port/in`: 인바운드 유스케이스 인터페이스
- `application/*/port/in/dto`: `Command`, `Result` DTO
- `application/*/port/out`: 아웃바운드 포트(레포지토리/조회 포트)
- `application/*/service`: 유스케이스 서비스 구현 (`@Service`)
- `presentation/user`, `presentation/department`: API 요청/응답 DTO + 컨트롤러
- `infrastructure/user`, `infrastructure/department`: JPA 어댑터 + QueryDSL 조회 구현

샘플 의존성:

- `spring-boot-starter-web`
- `spring-boot-starter-data-jpa`
- `querydsl-jpa` (`jakarta`)
- `com.h2database:h2`
- `archunit-junit5` (아키텍처 테스트)

실행:

```bash
./gradlew -p samples/consumer-test clean check
```

ArchUnit 테스트만 실행:

```bash
./gradlew -p samples/consumer-test test --tests "*HexagonalArchitectureTest"
```

샘플의 `HexagonalArchitectureTest`에서 검증하는 내용:

- `domain` -> `application/infrastructure/presentation` 의존 금지
- `domain` -> `org.springframework/jakarta.persistence/javax.persistence/com.querydsl` 의존 금지
- `application` -> `infrastructure/presentation` 의존 금지
- `presentation` -> `infrastructure` 의존 금지

애플리케이션 실행:

```bash
./gradlew -p samples/consumer-test bootRun
```

사용 예시:

- `User`는 `departmentId`(식별자)만 보관합니다.
- `GET /api/users/{userPublicId}`는 QueryDSL 조인으로 `departmentName`을 함께 조회합니다.

1) 부서 생성 `POST /api/departments`

```json
{
  "name": "Platform"
}
```

응답 예시:

```json
{
  "publicId": "8c87bf1a-ef3a-4c4e-98c0-456f8b2602be",
  "name": "Platform"
}
```

2) 사용자 생성 `POST /api/users`

```json
{
  "name": "song",
  "departmentId": "8c87bf1a-ef3a-4c4e-98c0-456f8b2602be"
}
```

응답 예시:

```json
{
  "publicId": "14f1f1a0-4ab5-4f1a-bf50-c9e0e8162e95",
  "name": "song",
  "departmentId": "8c87bf1a-ef3a-4c4e-98c0-456f8b2602be"
}
```

3) 사용자 조회(부서명 포함) `GET /api/users/{userPublicId}`

응답 예시:

```json
{
  "userPublicId": "14f1f1a0-4ab5-4f1a-bf50-c9e0e8162e95",
  "userName": "song",
  "departmentId": "8c87bf1a-ef3a-4c4e-98c0-456f8b2602be",
  "departmentName": "Platform"
}
```
