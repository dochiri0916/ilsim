# Querydsl 컨벤션

- 동적 검색·조인·집계는 `adapter.out.persistence`의 `{Domain}QueryRepository`에 둔다. CRUD·ID·고정 조건·잠금 조회는 `JpaRepository`에 둔다.
- `QueryRepository`는 `@Component` 구체 클래스로 작성하고 생성자로 주입한다. `PersistenceAdapter`가 저장소 포트를 구현하며 도메인 변환과 기술 예외 번역을 담당한다.
- 내부 검색 조건의 정의와 Query 변환은 [애플리케이션 컨벤션](application-conventions.md)을 따른다.
- 저장소 포트에 Querydsl·JPA·Spring Data의 조회 타입을 노출하지 않는다. 조회 결과는 도메인 또는 기술에 독립적인 DTO로 반환한다.
- 공통 `JPAQueryFactory` Bean은 `global.configuration.QuerydslConfiguration`에 둔다. Spring이 관리하는 `EntityManager`를 사용하고 호출마다 새 쿼리를 만든다.
- Q 타입은 Entity와 같은 패키지에 자동 생성하고 Git에 포함하지 않는다. 기본 인스턴스는 static import하고, 같은 Entity를 여러 번 조인하면 역할별 별칭을 만든다.
- 선택 조건은 `statusEq`·`priceGoe` 같은 `BooleanExpression` 메서드로 분리해 `where(...)`로 조합한다. 입력이 없으면 `null`을 반환하며 필수 접근 조건은 항상 적용한다.
- 페이징은 `page >= 0`, `1 <= size <= 100`을 검증하고 offset은 `(long) page * size`로 계산한다. 정렬에는 유일한 키를 포함하며 기본은 `createdAt DESC, id DESC`로 한다.
- 목록과 count는 같은 조건의 별도 쿼리로 작성하고 `fetchResults()`·`fetchCount()`를 사용하지 않는다. 컬렉션 조인으로 중복되면 `exists`·`countDistinct()` 등으로 조회 대상을 맞춘다.
- 컬렉션 fetch join과 페이징을 함께 사용하지 않는다. 페이지 조회 후 필요한 컬렉션을 일괄 조회하고 원래 순서로 조립해 N+1을 방지한다.
- 영속성 내부 DTO는 같은 `adapter.out.persistence`의 `{Purpose}Row` public record로 작성하고 `Projections.constructor(...)`로 매핑한다. `@QueryProjection`은 내부 Row에만 허용한다.
- 다른 BC의 Entity·저장소를 직접 조회하지 않는다. 로컬 스냅샷 등 기존 공개 계약을 사용하며 `persistence.projection`은 이벤트로 갱신하는 로컬 조회 모델에 사용한다.
- 조회 트랜잭션은 [애플리케이션 컨벤션](application-conventions.md)을 따른다. 쿼리 실행과 변환은 어댑터에서 `RepositoryExceptionTranslator.execute(...)`로 감싼다.

공통 규칙은 [애플리케이션](application-conventions.md), [어댑터](adapter-conventions.md), [영속성](persistence-conventions.md) 컨벤션을 따른다.
