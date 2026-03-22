# 인프라스트럭처 컨벤션

## 핵심 원칙

- 인프라스트럭처는 아웃바운드 포트 구현체(Adapter) 위치다.
- JPA 엔티티, QueryDSL, 외부 시스템 연동 코드는 인프라스트럭처에만 둔다.
- 도메인 모델과 영속 엔티티는 Mapper로 변환한다.

## 영속성 원칙

- 엔티티 클래스명은 단수형을 사용한다.
- `@Table(name = "...")` 테이블명은 복수형을 사용한다.
- 도메인 모델에는 `@Entity`를 사용하지 않는다.
- 식별자는 내부 ID와 공개 ID를 분리해서 관리한다.

## Repository/Adapter 원칙

- `*JpaAdapter`는 포트 인터페이스를 구현한다.
- `*JpaRepository`는 Spring Data JPA 인터페이스로 유지한다.
- 조회 최적화(QueryDSL 조인 등)는 별도 Query Repository/Adapter로 분리한다.

## SQL/Query 원칙

- DB 종속 SQL(native query)은 사용 목적과 대상 DB를 명확히 문서화한다.
- 조인 조회 결과는 애플리케이션 계층의 read model DTO로 반환한다.

## 자동 검증

- `validateEntityNamingConvention`
  - 엔티티 단수명/테이블 복수명
- `checkstyleMain`, `pmdMain`, `spotbugsMain`
  - 스타일/품질/버그 기본 검증
- 샘플 `HexagonalArchitectureTest`(ArchUnit)
  - `presentation -> infrastructure` 의존 금지 재검증

## 리뷰 검증

- 어댑터가 비즈니스 규칙을 가져가지 않는지
- mapper 없이 엔티티가 상위 레이어로 노출되지 않는지
