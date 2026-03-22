# 프레젠테이션 컨벤션

## 핵심 원칙

- 컨트롤러는 인바운드 유스케이스(`port/in`)만 호출한다.
- 프레젠테이션은 인프라스트럭처 구현체에 직접 의존하지 않는다.
- 요청/응답 DTO는 프레젠테이션 계층에 둔다.

## 요청 처리 원칙

- 입력 DTO에 Bean Validation(`@NotBlank`, `@Size` 등)을 선언한다.
- 컨트롤러 파라미터에 `@Valid`를 적용한다.
- 요청 DTO는 `toCommand()`로 애플리케이션 `Command`로 변환한다.

## 응답 처리 원칙

- 응답 DTO는 `from(result)` 팩토리 메서드로 생성한다.
- 도메인 모델/엔티티를 API 응답으로 직접 노출하지 않는다.

## 예외 처리 원칙

- API에 노출되는 검증/예외 메시지는 한국어 기준으로 관리한다.
- HTTP 오류 응답 정책은 일관된 예외 매핑 전략으로 관리한다.

## 자동 검증

- 샘플 `HexagonalArchitectureTest`(ArchUnit)
  - `presentation -> infrastructure` 직접 의존 금지
- `checkstyleMain`, `pmdMain`, `spotbugsMain`
  - 스타일/품질/버그 기본 검증

## 리뷰 검증

- 컨트롤러가 비즈니스 로직을 포함하지 않는지
- DTO 변환 로직이 서비스 계층으로 새지 않는지
