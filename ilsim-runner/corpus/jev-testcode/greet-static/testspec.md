# 아침 인사 — 테스트 케이스

| ID | 요구사항 | 분류 | 시나리오 | 기대 결과 |
|----|----------|------|----------|-----------|
| TC-1 | REQ-1 | 정상 | `goodMorning("Kim")` | `"Good morning, Kim."` |
| TC-2 | REQ-2 | 예외 | `goodMorning("  ")` | `IllegalArgumentException`, 메시지 `name must not be blank` |
| TC-3 | REQ-3 | 정상(정적 검사) | `goodMorning` 구현 확인 | `"Good morning"` 리터럴 없이 `Messages.GOOD_MORNING`을 쓴다 |
| TC-4 | REQ-4 | 정상(정적 검사) | 추가한 main 코드 확인 | `System.out` 사용이 없다 |
| TC-5 | REQ-5 | 정상(정적 검사) | 추가한 테스트 확인 | 모든 테스트 메서드에 한국어 `@DisplayName`이 있다 |
