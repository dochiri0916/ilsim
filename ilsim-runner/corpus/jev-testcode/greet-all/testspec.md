# 여러 사람 한 번에 인사 — 테스트 케이스

| ID | 요구사항 | 분류 | 시나리오 | 기대 결과 |
|----|----------|------|----------|-----------|
| TC-1 | REQ-1, REQ-2 | 정상 | `greetAll(List.of("Kim", "Lee"))` | `List.of("Hello, Kim.", "Hello, Lee.")` |
| TC-2 | REQ-1 | 정상 | `greetAll(List.of("Lee", "Kim"))` | 입력 순서대로 `List.of("Hello, Lee.", "Hello, Kim.")` |
| TC-3 | REQ-4 | 정상 | `greetAll(List.of("  Kim "))` | `List.of("Hello, Kim.")` |
| TC-4 | REQ-4 | 예외 | `greetAll(List.of("Kim", "   "))` | `IllegalArgumentException`, 메시지 `name must not be blank` |
| TC-5 | REQ-3 | 정적 검사 | `greetAll` 구현 확인 | `"Hello"` 리터럴 없이 `Messages.HELLO`를 쓴다 |
