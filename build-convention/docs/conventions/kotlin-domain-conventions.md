# Kotlin 도메인·VO 기준

플러그인 ID는 `io.github.dochiri0916.build-convention.kotlin`이다. `check`는 `validateKotlinDomainConventions`와 `validateKotlinArchUnitArchitecture`를 실행한다. 도메인 소스 기준 예시는 [주문 Kotlin 템플릿](../templates/kotlin/src/main/kotlin/com/example/saga/order/domain/Order.kt)이며 패키지 루트나 `Order` 이름을 하드코딩하지 않는다.

## 소스와 의존성

Kotlin PSI로 main의 `.kt` 파일을 파싱한다. 기본 `src/main/kotlin`, main source set의 사용자 지정 경로를 검사하며 컴파일러 source exclusion으로 규칙 위반을 숨길 수 없다. 구문 오류·읽기 실패·중복 타입은 `ARCH-SOURCE-SYNTAX`로 실패한다. 패키지 또는 실제 디렉터리에 `domain` 세그먼트가 있으면 도메인 대상으로 삼으며, 도메인 디렉터리에서 패키지만 다른 계층으로 바꾸는 우회도 실패한다.

도메인은 application·adapter·Spring/JPA 등 기술 프레임워크 및 다른 BC 도메인에 직접 의존하지 않는다. import와 완전한 이름의 참조를 검사하고, JVM 프로젝트에서는 컴파일된 클래스에 공통 ArchUnit 규칙도 적용한다. 공통 규칙은 도메인이 프로젝트 밖에서 `java`·`kotlin`·`org.jetbrains.annotations`만 사용하도록 허용 목록으로 검사하고, 어댑터가 `application.service`의 구현 클래스에 의존하는 것을 거부한다. `hexagonalConvention.basePackage`는 그룹화된 BC의 루트를 지정할 수 있다.

## 불변 도메인

- 클래스는 기본 final이며 상태는 `val`이다. `open`·`abstract`·`var`는 허용하지 않는다.
- 일반 도메인 모델의 생성자는 private이다. 검증 경로를 우회하는 `data class.copy`와 인자를 받는 보조 생성자는 허용하지 않는다.
- `id`를 가진 Aggregate는 `create` 또는 `register`, 그리고 `restore` companion 팩터리를 제공한다. 상태 변경과 복원은 전달된 ID를 유지하며 `restore`에서 ID·시간을 새로 생성하지 않는다.
- Aggregate의 `equals(Any?)`·`hashCode()`는 ID만 사용한다. 구성 값 객체와 일급 컬렉션은 모든 주 생성자 구성 값으로 동등성을 정의한다. 파생 금액 캐시는 동등성 구성 값에 추가하지 않는다.
- enum 상태는 `domain.vo`에 두고 상태 변경 전에 도메인 예외를 던지는 가드를 둔다. enum의 전이 정책은 허용·거절 결과를 모두 가져야 하며 상수 `true`로 검증을 제거할 수 없다. 구체적인 업무 전이 표는 소비 프로젝트의 테스트로 확인한다.

## VO

`@JvmInline value class`는 `domain.vo`에 두며 private 주 생성자에 하나의 `val`을 감싼다. 공개 `of` companion 팩터리를 제공한다. ID 외의 값 객체는 init에서 입력 불변식을 검증하며 Kotlin이 생성하는 value class의 동등성을 사용한다.

`Quantity`는 양수, `Price`는 음수 금지 규칙을 검사한다. 주석·문자열·실행되지 않는 lambda·죽은 분기에 적힌 조건을 검증 근거로 인정하지 않는다.

검사기는 init의 직접적인 if/else·throw, 괄호·논리 연산·비교 연산을 해석한다. import 별칭을 지원한다. 외부 검증 헬퍼나 임의 함수 호출을 불변식 증거로 추정하지 않으므로 이러한 구조는 직접 검증 가능한 가드로 표현하거나 별도 규칙·회귀 테스트를 추가해야 한다.

## ID VO와 UUID 경계

ID는 아래 세 가지 닫힌 계약을 따른다. PSI 구조를 비교하므로 공백·주석·import 별칭과 동일 타입의 완전한 이름은 허용하지만 다른 생성·검증 구현은 거절한다. 패키지 루트와 도메인 이름은 소비 프로젝트에서 읽는다.

- ID VO는 `domain.vo` 바로 아래의 `{Domain}Id`이며 `@JvmInline value class`, `private constructor(val value: UUID)`, `override fun toString(): String = value.toString()`, `companion object`의 `fun of(value: UUID): {Domain}Id = {Domain}Id(value)`만 허용한다.
- 입력 검증은 같은 컨텍스트의 `adapter.in`에 있는 공개 최상위 `fun UUID.requireV7(field: String): UUID`로 수행한다. private const `UUID_V7 = 7`·`RFC_VARIANT = 2`, `if (version() != UUID_V7 || variant() != RFC_VARIANT) throw InvalidUuidV7Exception(field, this)`, `return this`를 사용한다.
- 자체 ID마다 `application.port.out.{Domain}IdGenerator`의 `fun generate(): {Domain}Id`와 `adapter.out.identifier.UuidV7{Domain}IdGenerator`가 필요하다. 발급 어댑터는 `@Component` final 클래스이며 해당 포트를 구현하고 `override fun generate(): {Domain}Id = {Domain}Id.of(UuidCreator.getTimeOrderedEpoch())`만 제공한다.

ID VO의 nullable·String·Long 값, 일반/data/open 클래스, typealias, init 검증, 파싱·발급, 추가 상태·보조 생성자·메서드·팩터리는 금지한다. ID·companion의 확장 함수나 다른 계층의 타입 별칭으로 추가 API를 만들 수도 없다. 도메인은 UUID를 받은 그대로 감싸며 v7 형식 검증을 중복하지 않는다. ID의 동등성은 value class가 생성한다. [OrderId](../templates/kotlin/src/main/kotlin/com/example/saga/order/domain/vo/OrderId.kt)와 [OrderItemId](../templates/kotlin/src/main/kotlin/com/example/saga/order/domain/vo/OrderItemId.kt)가 예시다.

`InvalidUuidV7Exception`은 final `IllegalArgumentException`이며 생성자에 `val field: String`·`val value: UUID`를 가진다. 오류 메시지는 field를 포함한 문자열이다. 해당 입력 가드 파일에는 위의 상수 두 개·예외·가드만 둔다. 입력 가드는 `adapter.in`에서만 호출한다. [UuidV7](../templates/kotlin/src/main/kotlin/com/example/saga/order/adapter/in/UuidV7.kt)을 참고한다.

다른 BC의 ID는 로컬 `{Domain}ReferenceId` 래퍼로 표현하고 로컬 발급 포트·어댑터를 만들지 않는다. 입력 가드는 참조 ID에도 필요하다. UUID Creator는 `adapter.out.identifier`에서만 사용하며 random/name 기반 UUID·직접 UUID 생성자·대체 발급 함수·가짜 외부 타입 선언으로 발급 계약을 우회할 수 없다. [발급 어댑터](../templates/kotlin/src/main/kotlin/com/example/saga/order/adapter/out/identifier/UuidV7OrderIdGenerator.kt)를 참고한다.

검증 대상은 main Kotlin 소스의 ID 선언, 입력 가드 구현, 발급 포트·어댑터와 금지된 UUID 발급 경로다. 모든 요청·메시지가 실제로 가드를 거쳐 유스케이스로 전달되는지는 소비 프로젝트의 입력 변환 테스트로 확인한다.

## 일급 컬렉션과 예외

일급 컬렉션은 하나의 컬렉션 구성 값과 선택적인 파생 캐시를 소유한다. 모든 생성 경로에서 `java.util.List/Set/Map.copyOf`로 변경 불가능한 스냅샷을 만든다. `toList()`만으로 변경 불가능성을 보장했다고 판단하지 않는다. 저장 컬렉션의 변경 메서드와 mutable 타입 노출을 거절한다.

빈 입력은 직접 가드로 거절하거나 명시적인 `empty` 팩터리를 제공한다. 식별자를 가진 요소의 컬렉션은 init에서 로컬 HashSet과 forEach 가드로 중복 식별자를 거절한다. 숫자 fold 합산에는 `Math.addExact`를 사용한다. 금액 곱셈·업무 한도·모든 계산 경로의 오버플로까지 자동 증명하는 규칙은 아니다.

도메인 예외의 기반은 sealed `RuntimeException`이며 protected 생성자를 사용한다. 구체 예외는 final로 기반 예외를 상속한다. 도메인 실패는 해당 BC 또는 공통 global의 도메인 예외로 표현하며 파싱 과정의 JDK 예외를 그대로 던지지 않는다.

## 실패와 보고서

위반은 규칙 ID·파일 경로와 함께 Gradle 실행을 실패시키고 `build/reports/build-convention/report.json`에 `FAIL`을 남긴다. Java 플러그인도 적용한 혼합 모듈의 Kotlin 보고서는 `report-kotlin.json`이다. 기존 schemaVersion 1.0·check ID·상태 의미를 유지한다. Kotlin 파일 수·도메인 파일 수·위반 목록은 `kotlin` 필드에 기록한다.

태스크 disable·onlyIf·액션 변조·check 의존성 제거·`-x`·관련 컨벤션 플래그 완화는 무력화 방지 검사에서 실패한다. Kotlin 전용 플러그인은 Java 전용 Checkstyle/PMD/SpotBugs·coverage/mutation gate를 적용하지 않으며 보고서에 `NOT_APPLIED`로 표시한다. 소비 프로젝트의 기존 컴파일·테스트는 그대로 실행한다. 도메인 소스가 없으면 architecture는 `NOT_APPLIED`다.

`ai`의 full 컨벤션 검사는 Kotlin/JVM 모듈에 이 플러그인을 선택하고 혼합 모듈의 두 보고서를 합산한다. `code-risk-analyzer scan`의 위험 분석은 계속 Java 소스를 대상으로 한다. Kotlin 컨벤션 PASS는 임의 업무 로직이나 보안 위험 전체를 검증했다는 의미가 아니다.
