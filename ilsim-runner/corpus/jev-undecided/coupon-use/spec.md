# 쿠폰 사용

## 배경
주문 결제 때 회원이 가진 쿠폰을 쓸 수 있게 한다. `Coupon` 엔티티는 `memberId`, `discountAmount`, `expiresAt`, `used`를 가진다.

## 요구사항
- REQ-1: `CouponService.use(couponId, memberId, orderAmount)`는 쿠폰을 사용 처리하고 할인 금액을 돌려준다.
  - 인수 조건: 사용할 수 있는 쿠폰이면 `used`가 `true`가 되고 할인 금액을 반환한다.
- REQ-2: 다른 회원의 쿠폰이면 `CouponOwnerMismatchException`을 던진다.
- REQ-3: 이미 사용한 쿠폰이면 `CouponAlreadyUsedException`을 던진다.
- REQ-4: 만료된 쿠폰이면 `CouponExpiredException`을 던진다. 현재 시각은 주입한 `Clock`으로 얻는다.

## 영향 범위
- `src/main/java/com/x/coupon/CouponService.java`
- `src/main/java/com/x/coupon/Coupon.java`
- `src/main/java/com/x/coupon/CouponRepository.java`
- `src/main/java/com/x/coupon/` 아래 예외 클래스
- `src/test/java/com/x/coupon/CouponServiceTest.java`

## 미결정 사항
- Q1. 만료 시각과 현재 시각이 정확히 같으면 사용할 수 있는가?
- Q2. 할인 금액이 주문 금액보다 크면 주문 금액까지만 할인하는가, 사용을 거절하는가?
- Q3. 최소 주문 금액 조건을 두는가? 둔다면 금액은?
- Q4. 같은 쿠폰에 동시에 사용 요청이 오면 낙관적 락과 비관적 락 중 무엇으로 막는가?
