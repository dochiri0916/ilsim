# 템플릿 실행 조건

`kotlin/src/main/kotlin`에는 주문 도메인 18개 파일과 UUID 입력 가드·발급 포트·어댑터 5개 파일이 있다. [Kotlin 도메인 기준](../conventions/kotlin-domain-conventions.md)에 따라 Kotlin/JVM 2.3.21·JDK 21에서 컴파일과 전체 `check`를 검증한다. ID는 입력 어댑터에서 검증하고 출력 어댑터에서 발급하며 도메인 VO는 UUID 래퍼로만 사용한다. 다른 BC의 재고 ID는 `StockReferenceId`다. Kotlin 표준 라이브러리에 `com.github.f4b6a3:uuid-creator:6.1.0`·`org.springframework:spring-context:6.2.12` 의존성을 추가한다. 이 예시는 주문 도메인·VO·예외와 ID 경계를 다루며 Java/Spring 템플릿 실행 조건과 구분한다.

템플릿은 Java 21, Spring Boot 3.5.16과 MySQL 8.4.10에서 컴파일·실행 테스트했다. 결과와 검증 범위는 [template-verification.json](../conventions/template-verification.json)에 기록한다. 전체 컨벤션 적용과 소비 프로젝트의 플러그인 검증은 아직 진행 중이다.

기존 Spring Web·Validation·Data JPA, Lombok, UUID Creator 의존성에 Querydsl 의존성을 추가한다. 다음은 검증에 사용한 구성이다.

```groovy
implementation 'io.github.openfeign.querydsl:querydsl-jpa:7.7'
annotationProcessor 'io.github.openfeign.querydsl:querydsl-apt:7.7:jpa'
annotationProcessor 'jakarta.annotation:jakarta.annotation-api'
annotationProcessor 'jakarta.persistence:jakarta.persistence-api'
```

실행 시 `runtimeOnly 'com.mysql:mysql-connector-j'`를 추가하고 JDBC URL은 `jdbc:mysql://...`를 사용한다. JDBC 드라이버와 Jakarta API 버전은 Spring Boot BOM을 사용한다. Q 타입은 annotation processor가 생성하며 소스나 Git에 복사하지 않는다.

`SearchBooksQuery`와 `BookSearchCondition`은 페이지 범위를 각각 검증한다. 저장소는 같은 선택 조건 헬퍼를 목록/count에 적용하고 `createdAt DESC, id DESC`로 페이지 순서를 고정한다. `BookQueryRepositoryTest`는 같은 생성 시각, 선택 조건, 큰 offset을 실제 DB에서 확인한다. `GET /api/books?isbn=...&page=0&size=10`은 `SearchBooksQuery` → 서비스의 VO 변환 → 저장소 포트 → Querydsl로 이어진다. 내부 Row는 어댑터에서 기술 독립 DTO·일급 컬렉션으로 변환하고, ResultMapper와 Response 변환을 거쳐 JSON 목록으로 반환한다. `BookSearchIntegrationTest`는 실제 HTTP·서비스·DB 경로를 검증한다.

ISBN 중복 번역은 `uk_books_isbn`과 MySQL이 보고한 `books.uk_books_isbn`만 정확히 일치할 때 적용한다. 다른 테이블·제약·이름 접미사는 ISBN 중복으로 처리하지 않으며 원인을 보존한다. 이전 PostgreSQL 검증 기록은 `template-verification-postgresql.json`에 별도로 보관한다.

UUID v7 발급에는 검증에 사용한 `com.github.f4b6a3:uuid-creator:6.1.1` 의존성이 필요하다. 기존 DB에서는 자체 식별자의 36자 UUID 저장을 위해 애플리케이션 시작 전에 컬럼과 제약을 마이그레이션한다.

## 실행 근거 연결

소비 프로젝트에서 템플릿을 사용할 때도 report.json의 conventionReview를 확인한다. [runtime-verification.md](../conventions/runtime-verification.md)의 선언 형식으로 해당 프로젝트에서 실제 실행한 테스트를 연결한다.

- BookQueryRepositoryTest의 usesStablePagesAndSeparateCount(), sharesOptionalFilterAcrossListAndCount(), supportsLargeOffset()는 안정 정렬·목록/count 필터·큰 offset의 근거다.
- BookSearchIntegrationTest의 searchesThroughRealLayers(), rejectsInvalidPagination()는 실제 HTTP 변환과 잘못된 페이지의 근거다.
- 이 예제는 컬렉션 조인·자식 일괄 조회·기본/safe의 복수 구현·메시지 브로커·Outbox/Inbox 전달 보장을 검증하지 않는다. 해당 기능을 추가하면 소비 프로젝트의 DB/브로커 테스트와 운영 검토가 필요하다. 기존 template-verification 파일을 새 소비 프로젝트의 완료 근거로 복사하지 않는다.
