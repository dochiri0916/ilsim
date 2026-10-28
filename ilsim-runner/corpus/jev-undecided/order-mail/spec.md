# 주문 완료 메일

## 요구사항
- REQ-1: 주문이 결제 완료(`OrderPaidEvent`)되면 주문자에게 주문 완료 메일을 보낸다.
- REQ-2: 메일 본문에는 주문번호, 상품 목록, 결제 금액을 넣는다.
- REQ-3: 메일은 `MailSender` 포트로 보내고, 보낸 기록을 `MailLogRepository`에 남긴다.

## 영향 범위
- `src/main/kotlin/com/x/mail/OrderMailListener.kt` (새 파일)
- `src/main/kotlin/com/x/mail/OrderMailContent.kt` (새 파일)
- `src/test/kotlin/com/x/mail/OrderMailListenerTest.kt`

## 미결정 사항
- Q1. 발송에 실패하면 다시 보내는가? 다시 보낸다면 몇 번인가?
- Q2. 마케팅 수신을 거부한 회원에게도 보내는가?
- Q3. 메일 제목 형식은 무엇인가?
- Q4. 메일은 트랜잭션 커밋 뒤 같은 스레드에서 보내는가, 비동기로 보내는가?
