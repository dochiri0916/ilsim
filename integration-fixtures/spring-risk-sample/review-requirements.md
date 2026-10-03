# 검증 fixture의 요구사항과 의도된 결함

이 문서는 분석기 검증용 fixture의 관측 계약이다. 운영 서비스의 요구사항으로 사용하지 않는다.

REQ-PRICE: 수량 1 미만은 IllegalArgumentException, 수량 1~9는 할인 0%, 10 이상은 10%다.
REQ-UNIQUE: SafeService의 같은 business/message key 중복은 충돌이며 최종 1행이다. 병렬 생성은 1개 commit과 1개 conflict다.
REQ-ROLLBACK: SafeService.saveThenFail과 UnsafeService.saveThenFail의 직접 proxy 호출은 예외와 최종 0행이다.
REQ-EXISTING: UnsafeService.createIfAbsent의 순차 기존 key 재호출은 행을 추가하지 않아 최종 1행이다.
REQ-UNSAFE-CON: 의도된 결함. UnsafeService의 두 absent 조회를 barrier로 맞추면 두 삽입이 commit해 최종 2행이다. CON-001 위험은 해소되지 않는다.
REQ-UNSAFE-TX: 의도된 결함. selfInvokeThenFail의 내부 호출은 transaction proxy를 우회한다. 예외 후 repository commit 1행이 남는다. TX-001 위험은 해소되지 않는다.
REQ-UNSAFE-MSG: 의도된 결함. UnsafeService.handleMessage의 같은 ID 반복은 최종 2행이다. 안전한 멱등 처리로 판정하지 않는다.
REQ-START: 로컬 서버 start는 기동 상태를 true로 만들며 재호출은 같은 포트를 유지한다. stop 후 다시 start할 수 있다.
REQ-STOP: 기동한 서버 stop은 기동 상태를 false로 만들고 이전 포트의 연결을 종료한다.
REQ-STOP-EMPTY: 아직 기동하지 않은 서버의 반복 stop도 예외 없이 false 상태를 유지하고 이후 정상 start가 가능해야 한다.
REQ-BIND-FAIL: 실제 점유된 loopback 포트의 bind IOException은 IllegalStateException으로 전달되고 기동 상태는 false여야 한다. 반복 실패와 stop이 안전하며, 포트 해제 후 같은 포트로 start를 재시도할 수 있고 최종 stop 뒤 포트를 반환해야 한다.
REQ-PORT: port는 실제 loopback 서버의 bound 포트다. 반환한 포트를 사용한 실제 HTTP readiness 요청이 성공해야 한다.

검토자는 실제 assertion·현재 소스·MySQL 실행 결과를 연결한다. 정적 PARTIAL/MISSING은 별도 보존한다.
정적 분석의 한계로 분류해도 동작 정확성을 자동 증명한 것으로 표현하지 않는다.
