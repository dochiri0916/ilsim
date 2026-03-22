JPA를 처음 쓸 때는 연관관계를 잘 맺는 것이 정석이라고 믿었습니다. 객체 탐색이 편하고, 필요한 데이터도 자연스럽게 따라올 것 같았습니다.

하지만 운영 구간에서 반복해서 마주친 문제는 비슷했습니다.

- 어디서 쿼리가 추가로 실행되는지 예측이 어려움
- 화면 요구가 늘어날수록 연관 탐색 범위가 넓어짐
- 성능 이슈 발생 시 요청 로그를 뒤져 원인을 찾는 방식에 의존

그래서 질문을 다시 잡았습니다.

- 연관관계 매핑은 언제 이점이 큰지
- 식별자 참조는 언제 비용을 줄이는지
- N+1은 어디에서 통제해야 하는지

이 글은 `osiv` 예제 프로젝트에서 읽기/쓰기 경로를 나눠 실험한 결과를 바탕으로 작성했습니다.

`OSIV(Open Session In View)`는 요청 종료 시점까지 영속성 컨텍스트를 유지해 웹 계층 지연 로딩을 허용하는 설정입니다. 편의성은 높지만 쿼리 실행 책임이 서비스 계층 밖으로 퍼질 수 있어, 이번 실험에서는 `spring.jpa.open-in-view=false`를 고정하고 비교했습니다.

---

## 문제를 재현한 방식

프로젝트는 같은 도메인에서 읽기 경로를 두 가지로 나눴습니다.

1. 연관관계 탐색 경로: `GET /api/osiv/posts`
2. 식별자 참조 조회 경로: `GET /api/osiv/posts-id-reference`

쓰기 경로도 두 가지로 나눴습니다.

1. 엔티티 조회 후 생성: `POST /api/osiv/comments/load`
2. 식별자 참조 후 생성: `POST /api/osiv/comments/id-reference`

핵심 설정은 다음과 같습니다.

```yaml
spring:
  jpa:
    open-in-view: false
```

이번 실험에서 본 지표는 아래 두 가지였습니다.

- 요청 처리 중 실행된 SQL 개수
- SQL이 실행되는 계층 경계(서비스 내부 / 웹 계층 응답 생성 과정)

---

## 관찰된 사실

`N+1`은 연관관계 자체보다 읽기 경로의 로딩 계획이 불명확할 때 반복됐습니다.

현재 프로젝트에서도 같은 패턴이 나왔습니다.

- `posts` 경로는 엔티티 기반 응답 생성 과정에서 연관 데이터 접근이 발생
- `posts-id-reference` 경로는 서비스에서 필요한 데이터를 먼저 수집해 DTO로 반환

즉, 연관관계를 유지해도 읽기 계획을 명시하지 않으면 같은 문제가 다시 나옵니다.

OSIV도 같은 맥락이었습니다.

- OSIV가 켜져 있으면 웹 계층에서 지연 로딩이 가능하다
- 초기 개발은 편해지지만, 쿼리 책임 경계가 서비스 밖으로 밀릴 수 있다

그래서 이 프로젝트는 `open-in-view=false`로 경계를 먼저 고정했습니다.

---

## 식별자 참조가 만든 변화

식별자 참조는 "연관 대상의 상세 상태가 당장 필요 없는 쓰기"에서 효과가 컸습니다.

이 프로젝트의 쓰기 메서드는 다음 두 가지입니다.

```java
@Transactional
public Long createCommentByEntityLoad(Long postId, String content) {
    Post post = postRepository.findById(postId)
            .orElseThrow(() -> new IllegalArgumentException("Post not found: " + postId));
    Comment comment = new Comment(content, post);
    commentRepository.save(comment);
    return comment.getId();
}

@Transactional
public Long createCommentByIdReference(Long postId, String content) {
    Post postIdReference = postRepository.getReferenceById(postId);
    Comment comment = new Comment(content, postIdReference);
    commentRepository.saveAndFlush(comment);
    return comment.getId();
}
```

`p6spy`로 SQL 개수를 측정한 결과는 다음과 같았습니다.

- `comments/load`(`findById`): `SELECT + INSERT` -> `2`개
- `comments/id-reference`(`getReferenceById`): `INSERT` -> `1`개

실제 테스트 출력:

```text
SQL count comparison | entity-load=2, id-reference=1
```

핵심은 명확했습니다.

**식별자 참조의 주효과는 N+1 해결이 아니라, 쓰기 경로의 불필요 선조회를 줄이는 것입니다.**

여기서 중요한 점은 "어떤 조회를 생략했는지"입니다.

- `findById` 방식은 부모 엔티티 존재 확인을 위해 즉시 `SELECT`가 실행된다
- `getReferenceById` 방식은 FK 세팅에 필요한 참조만 먼저 잡고, 즉시 조회를 생략한다
- 단, 이후 부모 상태를 실제로 접근하면 그 시점에 조회 또는 예외가 발생할 수 있다

---

## 해석과 적용 기준

이 실험을 통해 정리한 기준은 다음입니다.

1. 식별자 참조의 주효과: 쓰기 경로 조회 최소화
2. N+1 통제의 핵심: 읽기 경로 로딩 계획 명시
3. OSIV 비활성화의 목적: 웹 계층 지연 로딩 경로 차단

따라서 읽기에서는 DTO 중심 조회와 전용 조회 리포지토리로 계획을 고정하는 편이 안정적이었습니다.

반대로 아래 경우에는 식별자 참조만으로 처리하면 안 됩니다.

- 존재 여부를 즉시 보장해야 하는 경우
- 권한/소유권 검증이 필요한 경우
- 연관 대상 현재 상태에 따라 비즈니스 규칙이 달라지는 경우

즉, 식별자 참조는 만능 규칙이 아니라 유스케이스 선택지입니다.

---

## 트레이드오프

이 기준에도 비용은 있습니다.

- 읽기/쓰기를 분리하면서 조회 전용 코드가 늘어납니다
- 팀이 익숙해지기 전에는 "왜 이렇게 나누는지" 학습 비용이 듭니다
- 식별자 참조의 예외 시점(실제 접근 시점)을 이해하지 못하면 장애 분석이 어려울 수 있다

대신 얻는 이점도 명확했습니다.

- SQL 실행 지점을 예측하기 쉬워집니다
- N+1을 운영에서 뒤늦게 찾기보다 설계 단계에서 차단할 수 있다
- 쓰기 경로에서 불필요한 선조회를 줄여 트랜잭션 부담을 낮출 수 있다

---

## 적용 범위

잘 맞는 경우:

- 조회 트래픽이 크고 화면별 조회 요구가 자주 바뀌는 서비스
- N+1이나 불필요 쿼리 이슈가 반복되는 팀
- API 응답 모델과 도메인 모델을 분리하려는 경우

과할 수 있는 경우:

- 조회 패턴이 고정된 단순 내부 시스템
- 팀이 아직 JPA 기본 규칙을 정착하는 단계라 분리 비용이 큰 경우

---

## 정리

이 프로젝트에서 얻은 결론은 다음입니다.

1. OSIV를 꺼서 웹 계층 지연 로딩 경로를 닫는다
2. 쓰기에서는 식별자 참조로 불필요 조회를 줄인다
3. 읽기에서는 반환 모델을 먼저 정하고 로딩 계획을 명시한다

한 줄로 요약하면,

"식별자 참조를 쓰면 N+1이 자동 해결되는 것이 아니라, 읽기와 쓰기 책임을 분리할 때 비로소 N+1을 통제할 수 있다."

---

## 소스 코드

[osiv](https://github.com/dochiri0916/osiv)

---

## 참고

- https://docs.spring.io/spring-boot/appendix/application-properties/index.html#application-properties.data.spring.jpa.open-in-view
- https://docs.spring.io/spring-data/jpa/docs/current/api/org/springframework/data/jpa/repository/JpaRepository.html#getReferenceById%28ID%29
- https://jakarta.ee/specifications/persistence/3.2/apidocs/jakarta.persistence/jakarta/persistence/entitymanager#getReference%28java.lang.Class,java.lang.Object%29
- https://jakarta.ee/specifications/persistence/3.2/apidocs/jakarta.persistence/jakarta/persistence/entitynotfoundexception