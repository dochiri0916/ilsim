# 예외 컨벤션

- 업무 규칙 위반은 `domain.exception`, 조회 실패·입력 계약 위반·유스케이스 수행 실패는 `application.exception`에 둔다.
- 기반 예외는 `abstract sealed {Domain}DomainException`·`{Domain}ApplicationException`으로 작성하고 `RuntimeException`을 상속한다. 생성자는 `protected`, 구체적인 예외는 `final`, 파라미터 선언은 [공통 코드 컨벤션](coding-conventions.md)을 따른다.
- 공통 VO 예외는 `global.domain.exception`의 `GlobalDomainException`을 상속한다.
- 예외 이름은 실패 의미를 드러낸다. 예: `{Value}RequiredException`, `Invalid{Value}Exception`, `{Domain}NotFoundException`, `Duplicate{Value}Exception`.
- 메시지는 한국어로 작성하고 민감한 값을 담지 않는다. 진단에 필요한 값은 `private final` 필드와 접근자로 제공할 수 있다.
- 도메인 예외는 그대로 전달하되 유스케이스 계약에 맞는 변환이 필요한 경우 해당 타입만 잡아 애플리케이션 예외로 변환한다.
- 주문 소유자만 취소할 수 있다는 업무 규칙의 위반은 `NotOrderOwnerException` 같은 도메인 예외로 표현한다. 인증된 요청자의 역할·권한 부족 등 유스케이스 접근 권한 실패는 애플리케이션 예외로 표현한다. HTTP 403으로 매핑된다는 이유만으로 도메인 예외를 애플리케이션 예외로 변환하지 않는다. 변환은 유스케이스가 별도의 실패 의미를 계약으로 제공해야 할 때만 수행하고 원인을 보존한다.
- 저장소 기술 예외는 출력 어댑터에서 번역한다. 해당 유니크 제약 위반만 중복·충돌 예외로 변환하고, 나머지는 `RepositoryExceptionTranslator`로 원인을 보존한 `RepositoryOperationException`을 전달한다.
- 장바구니 생성 충돌은 `uk_carts_member_id` 위반일 때만 업무 예외로 전달하고 웹 어댑터에서 409로 매핑한다.
- HTTP 상태와 오류 코드는 웹 어댑터에서 매핑한다. 예외를 추가할 때 기반 계층의 `permits`와 웹 매핑을 함께 갱신한다.
- 업무·공통 예외 처리기는 `HIGHEST_PRECEDENCE`, 미처리 예외의 전역 처리기는 `LOWEST_PRECEDENCE`를 사용한다.
- HTTP 상태는 입력 오류 400, 조회 실패 404, 소유권·로그인 실패 403, 업무 충돌 409를 기준으로 하되 기존 개별 매핑을 유지한다. 오류 코드는 대문자와 밑줄로 작성한다.
- 오류 응답은 `ProblemDetails.create()`로 생성하고 `code`·`timestamp`를 포함한다. 저장소·미처리 예외는 500·`INTERNAL_ERROR`와 공통 메시지로 응답하며 내부 상세 정보를 노출하지 않는다.
- 업무 예외는 `info` 또는 `warn`, 저장소·미처리 예외는 `error`로 기록한다. 기술 오류는 요청 URI·HTTP 메서드와 원인 예외의 스택 트레이스를 포함한다.

계층별 책임은 [도메인](domain-conventions.md), [애플리케이션](application-conventions.md), [어댑터](adapter-conventions.md), 저장소의 flush와 트랜잭션 실패 처리는 [영속성](persistence-conventions.md), 예외 검증은 [테스트](test-conventions.md) 컨벤션을 따른다.

## 예외 선언과 웹 경계 보완

- 기반 예외의 `permits`에는 실제 존재하는 모든 final 직계 예외를 명시한다. 구체 예외는 public constructor와 문자열 메시지를 사용할 수 있다.
- 공통 `ErrorCode`, `ErrorKind`, `BusinessException` 계약을 만들지 않는다.
- 웹 처리기는 sealed 예외를 `switch`로 나눠 상태와 코드를 매핑한다. Controller와 처리기에서 Spring `ProblemDetail` 팩터리를 직접 호출하지 않는다.
- `ProblemDetail`, `HttpStatus`, `@RestControllerAdvice`는 `global.exception` 또는 `adapter.in.web`에만 둔다.
- Domain·Application·Outbound Port는 DB·HTTP·SDK·Spring 기술 예외를 외부 계약으로 노출하지 않는다. 저장소 기술 예외는 어댑터에서 번역한다.
- `@Valid @RequestBody` 입력 검증 실패도 공통 오류 응답 형식을 사용한다. 거부된 필드 값 대신 입력 형식을 확인하라는 공통 400 메시지를 제공한다.

기반 예외 선언 예시:

```java
public abstract sealed class CartDomainException extends RuntimeException
        permits CartIdRequiredException {
    protected CartDomainException(final String message) {
        super(message);
    }
}

public final class CartIdRequiredException extends CartDomainException {
    public CartIdRequiredException() {
        super("장바구니 ID는 필수입니다.");
    }
}
```

실행 가능한 처리기와 공통 응답 예시는 [템플릿](../templates/README.md)을 참고한다.
