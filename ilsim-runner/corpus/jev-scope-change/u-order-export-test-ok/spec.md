# 주문 CSV 내보내기

## 요구사항
- REQ-1: `GET /admin/orders/export?from=YYYY-MM-DD&to=YYYY-MM-DD`는 기간 안 주문을 CSV로 내려준다. 열은 주문번호, 주문일시, 금액, 상태 순서다.
- REQ-2: 응답 `Content-Type`은 `text/csv; charset=utf-8`이고 파일 이름은 `orders-<from>-<to>.csv`이다.
- REQ-3: `ADMIN` 역할만 호출할 수 있다.

## 영향 범위
- `src/orders/orders-export.controller.ts` (새 파일)
- `src/orders/orders-export.service.ts` (새 파일)
- `src/orders/orders.module.ts`
- `test/orders-export.e2e-spec.ts`

## 미결정 사항
- Q1. 주문일시 형식은 ISO-8601인가, `yyyy-MM-dd HH:mm`인가? 시간대는 UTC인가 KST인가?
- Q2. 한 번에 내보낼 수 있는 기간이나 건수에 상한을 두는가?
- Q3. 취소된 주문도 포함하는가?
- Q4. 엑셀에서 한글이 깨지지 않게 UTF-8 BOM을 붙이는가?


## 사용자가 정한 사항

요구사항 승인 뒤 사용자가 정했다. 요구사항 문서와 같게 따른다.
- Q1: ISO-8601, UTC로 쓴다
