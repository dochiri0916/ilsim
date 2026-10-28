# 주문 취소 규칙

## 요구사항
- R1: 배송이 시작된(SHIPPED, DELIVERED) 주문은 취소할 수 없고 OrderNotCancellableException을 던진다.
- R2: 주문을 취소하면 주문한 모든 상품의 수량만큼 재고를 되돌린다.
- R3: 취소할 때 받은 취소 사유를 주문에 저장한다.

## 영향 범위
- `src/main/java/com/x/order/OrderService.java`

## 기존 기능 영향
- 주문 생성과 결제 흐름은 바꾸지 않는다.
