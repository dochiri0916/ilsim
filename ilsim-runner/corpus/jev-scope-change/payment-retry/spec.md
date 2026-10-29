# 결제 재시도

## 요구사항
- R1: 외부 결제 API 호출의 읽기 타임아웃을 3초로 설정한다.
- R2: 결제 호출이 실패하면 재시도해 최대 3번까지 시도한다.
- R3: 모든 시도가 실패하면 결제 상태를 FAILED로 저장한다.

## 영향 범위
- `src/main/java/com/x/payment/PaymentService.java`
- `src/main/java/com/x/payment/PaymentClientConfig.java`

## 기존 기능 영향
- 결제 요청 형식과 응답 처리는 바꾸지 않는다.
