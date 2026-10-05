# 컨벤션 적용 기록

기준 문서는 [conventions](conventions)에 보관한다. [coverage.json](conventions/coverage.json)은 각 필수 항목과 구현·검증 근거를 연결하며 미완료 항목은 pending으로 남긴다. 정적 검사로 판정할 수 없는 항목은 review로 표기하고 evidence에 확인 절차(DB 통합 테스트·코드 리뷰)를 적는다.

적용 순서:

1. 패키지 구조와 공통 코드·생활 체조 기반
2. Domain 보완과 Application 계약·흐름
3. 예외·웹 Adapter
4. Persistence·Querydsl·DB 변경
5. BC 이벤트·Outbox/Inbox
6. 테스트 규칙·템플릿·리포트·우회 방지 검증

실제 DB 데이터·외부 브로커·운영 보관 정책이나 업무 의미는 정적 검사만으로 증명할 수 없다. 해당 항목은 판정 범위와 실행 가능한 검증 또는 리뷰 절차를 별도로 명시한다. 구조 검증의 PASS를 운영 보장의 증명으로 해석하지 않는다.
