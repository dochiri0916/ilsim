# 쿠폰 사용 제한

## 요구사항
- R1: 만료된 쿠폰(만료 시각이 지난 쿠폰)은 사용할 수 없고 CouponExpiredException을 던진다.
- R2: 한 주문에는 쿠폰을 하나만 사용할 수 있다.
- R3: 쿠폰을 사용하면 사용 시각을 기록한다.

## 영향 범위
- `src/main/java/com/x/coupon/CouponService.java`
- `src/main/java/com/x/coupon/Coupon.java`

## 기존 기능 영향
- 쿠폰 발급과 조회는 바꾸지 않는다.
