# 결제 승인 재시도

## 배경
PG 승인 요청(`PgClient.approve`)이 일시적 오류로 실패하면 결제가 바로 실패한다. 일시적 오류는 다시 시도해 결제 성공률을 높인다.

## 요구사항
- REQ-1: `PaymentService.approve(orderId)`는 `PgClient.approve`가 `PgTemporaryException`을 던지면 다시 시도한다.
- REQ-2: `PgRejectedException`(카드 거절)은 다시 시도하지 않고 바로 `PaymentFailedException`으로 바꿔 던진다.
- REQ-3: 다시 시도해 성공하면 결제를 `APPROVED`로 저장한다.
- REQ-4: 다시 시도를 모두 실패하면 `PaymentFailedException`을 던진다.
- REQ-5: 시도마다 `payment.retry` 로그를 남긴다(주문 ID, 시도 번호).

## 영향 범위
- `src/main/java/com/x/payment/PaymentService.java`
- `src/main/java/com/x/payment/PaymentRetryProperties.java` (새 파일)
- `src/main/resources/application.yml`
- `src/test/java/com/x/payment/PaymentServiceTest.java`

## 미결정 사항
- Q1. 최대 시도 횟수는 몇 번인가?
- Q2. 시도 사이 대기 시간은 얼마인가?
- Q3. 다시 시도를 모두 실패하면 주문 상태를 바꾸는가? 바꾼다면 어떤 상태인가?
- Q4. PG 응답 타임아웃(`PgTimeoutException`)도 일시적 오류로 보고 다시 시도하는가?
