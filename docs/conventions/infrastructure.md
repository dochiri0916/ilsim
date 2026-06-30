# 아웃바운드 어댑터 컨벤션

## 핵심 원칙

- `adapter.out`은 아웃바운드 포트 구현체 위치다.
- JPA 엔티티, QueryDSL, 외부 시스템 연동 코드는 아웃바운드 어댑터에만 둔다.
- 도메인 모델과 영속 엔티티는 Mapper로 변환한다.
- 아웃바운드 어댑터 구현체는 `ContextConfig`에서 `@Bean`으로 조립하지 않고 `@Component` 또는 `@Repository`로 등록한다.

## 영속성 원칙

- 엔티티 클래스명은 단수형을 사용한다.
- `@Table(name = "...")` 테이블명은 복수형을 사용한다.
- 도메인 모델에는 `@Entity`를 사용하지 않는다.
- DB 기술 키 `Long id`와 도메인 식별자 VO의 문자열 값을 저장하는 `{target}Id` 컬럼을 분리해서 관리한다.

## Repository/Adapter 원칙

- `*JpaAdapter`는 포트 인터페이스를 구현한다.
- `*JpaRepository`는 Spring Data JPA 인터페이스로 유지한다.
- 조회 최적화(QueryDSL 조인 등)는 별도 Query Repository/Adapter로 분리한다.

## SQL/Query 원칙

- DB 종속 SQL(native query)은 사용 목적과 대상 DB를 명확히 문서화한다.
- 조인 조회 결과는 애플리케이션 계층의 read model DTO로 반환한다.
- migration의 도메인 식별자 컬럼은 `user_id varchar(32) unique`처럼 `{target}_id varchar(32) unique` 형태를 기본으로 한다.
- 다른 식별 가능 객체 참조는 `department_id`처럼 `{target}_id` 컬럼으로 저장하고 인덱스와 FK를 명시한다.
- FK는 DB 기술 키 `id`가 아니라 대상 테이블의 도메인 식별자 컬럼을 참조한다.

## 자동 검증

- `validateEntityNamingConvention`
  - 엔티티 단수명/테이블 복수명
- `validateClaudeConventions`
  - 기술 어노테이션(`@Entity`, `@Table`, `@Repository`)의 `adapter.out` 위치 검증
  - JPA Entity의 `@Getter`, protected `@NoArgsConstructor`, `Long id`, `String {target}Id` 구조 검증
  - JPA Entity/JpaRepository/PersistenceAdapter의 persistence 패키지와 구현 규칙 검증
  - 아웃바운드 어댑터 구현체의 `@Component`/`@Repository` 등록 검증
  - JPA 연관관계 어노테이션과 Entity 객체 참조 필드 금지
  - Mapper의 final class/private constructor/Spring Bean 등록 금지 검증
- `validateMigrationConventions`
  - SQL migration의 `{target}_id` 길이/unique, 참조 식별자 컬럼 인덱스/FK, 기술 id 참조 금지 검증
- `checkstyleMain`, `pmdMain`, `spotbugsMain`
  - 스타일/품질/버그 기본 검증
- `validateArchUnitArchitecture`
  - 레이어 간 금지 의존성 재검증

## 리뷰 검증

- 어댑터가 비즈니스 규칙을 가져가지 않는지
- mapper 없이 엔티티가 상위 레이어로 노출되지 않는지
