# 템플릿 실행 조건

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
