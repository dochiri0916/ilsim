# 결제 승인 재시도 — 테스트 케이스

| ID | 요구사항 | 분류 | 시나리오 | 기대 결과 |
|----|----------|------|----------|-----------|
| TC-1 | REQ-1, REQ-3 | 정상 | 첫 시도 `PgTemporaryException`, 두 번째 성공 | 결제 `APPROVED` 저장 |
| TC-2 | REQ-2 | 예외 | `PgRejectedException` | 다시 시도 없이 `PaymentFailedException` |
| TC-3 | REQ-4 | 예외 | 설정한 최대 시도 횟수만큼 모두 `PgTemporaryException` | `PaymentFailedException` |
| TC-4 | REQ-5 | 정상 | 두 번 시도 | `payment.retry` 로그 2건(주문 ID, 시도 번호) |
