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

## 검사 범위와 조회 안정성 보완

- 가변 `JPAQuery`를 필드에 보관하거나 외부에 반환하지 않는다.
- Q 타입의 Git 포함 여부는 프로젝트 아래 Java 파일의 Git index 스냅샷으로 검사한다. staging 후 작업 파일을 수정·삭제해도 검사 대상에서 빠지지 않는다. Git이 없는 소스 배포본에는 index 검사를 적용하지 않는다.
- 유일 정렬 키는 `id`·`publicId` 또는 조회 Entity의 NOT NULL 단일 열 유니크 제약에 해당하는 키를 포함한다. 지역 정렬 변수와 인자 없는 지역 헬퍼도 검사한다.
- offset 곱셈을 지역 변수·지역 헬퍼로 옮겨도 첫 곱셈부터 long으로 계산한다. 곱셈 후 long으로 캐스팅하지 않는다.
- 외부 헬퍼의 offset 계산, 복합 유일 키 전체 정렬, 조인 후 실제 유일성, 목록/count의 동일 조건, N+1 방지는 DB 테스트와 코드 리뷰로 확인한다. 구현·검증 범위는 [coverage.json](coverage.json)을 참고한다.

## 실행 근거

- 실제 DB에서 페이지 범위·큰 offset·동률 정렬은 QUERY-PAGINATION으로, 목록/count 조건·조인 중복·자식 일괄 조회와 N+1은 QUERY-RESULTS로 확인한다.
- 템플릿 테스트나 정적 검사 통과는 소비 프로젝트의 해당 동작을 증명하지 않는다. 해당 프로젝트에서 실행한 테스트를 [실행 근거](runtime-verification.md)에 연결한다.
