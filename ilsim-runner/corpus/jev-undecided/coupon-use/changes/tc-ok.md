# 쿠폰 사용 — 테스트 케이스

| ID | 요구사항 | 분류 | 시나리오 | 기대 결과 |
|----|----------|------|----------|-----------|
| TC-1 | REQ-1 | 정상 | 본인 쿠폰(할인 3,000원, 만료 하루 전), 주문 금액 50,000원 | 3,000 반환, `used = true` |
| TC-2 | REQ-2 | 예외 | 다른 회원의 쿠폰 사용 | `CouponOwnerMismatchException` |
| TC-3 | REQ-3 | 예외 | 이미 사용한 쿠폰 사용 | `CouponAlreadyUsedException` |
| TC-4 | REQ-4 | 예외 | 만료 시각 1초 뒤 사용 | `CouponExpiredException` |

## 요구사항 연결
- REQ-1: TC-1
- REQ-2: TC-2
- REQ-3: TC-3
- REQ-4: TC-4

## 제외한 케이스
- 만료 시각과 같은 시각(Q1), 할인 금액이 주문 금액보다 큰 경우(Q2), 최소 주문 금액(Q3), 동시 사용(Q4)은 미결정이라 TC를 만들지 않는다.
