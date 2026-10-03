# 검증용 프로젝트 작업 규칙

- 이 프로젝트는 분석기 본체와 분리된 실행·분석 대상이다. 루트 빌드에 subproject로 편입하지 않는다.
- `UnsafeService`와 `UnsafeRecord`의 결함은 의도된 검증 대상이다. 요구사항 변경 없이 안전한 구현으로 수정하지 않는다.
- 새 테스트 작성 전에 영향받는 기존 기능·테스트를 확인하고 기존 테스트 및 전체 검증을 실행해 소스 버전, 환경, 명령과 결과를 기준선으로 기록한다. 공통 절차는 [WORKFLOW_IMPLEMENTATION_STATUS.md](../../docs/status/WORKFLOW_IMPLEMENTATION_STATUS.md)의 변경 전 기준선과 기존 동작 보호 규칙을 따른다.
- 기존 동작의 중요한 테스트 공백을 먼저 보완하고 현재 구현에서 통과하는지 확인한 뒤 신규·수정 동작의 테스트를 작성한다. 구현 후 동일한 회귀 검증을 실행해 기준선과 비교하고 의도한 차이·예상 밖 차이·미검증 범위를 기록한다.
- 정적 scan을 테스트 작성 전·테스트 보완 후·최종 구현 후에 실행하고 위험·Obligation·연결된 테스트와 불확실 항목을 검토한다. 현재 자동 지원하지 않는 영향 추적·계약 변경 비교는 소스 검토로 보완한다.
- 업무 정책은 도메인·단위 테스트로, 저장·제약·transaction·동시성·중복 처리는 실제 MySQL을 사용하는 통합 테스트로 검증한다. API가 추가되는 작업에는 관련 API·계층 테스트와 실제 서비스 진입점 검증도 포함한다. 테스트 종류는 영향과 요구사항에 맞춰 선택한다.
- 테스트 작성 단계에서는 테스트 코드만 작성·수정한다. production 코드 작성·수정, 기존 동작 코드 변경, 신규 동작 코드 추가 및 production stub 추가를 금지한다.
- 테스트용 fixture·mock·helper는 테스트 소스에만 둔다. 구현 부재로 인한 실패는 기록하고 동작 코드 구현 단계에서 해결한다.
- 실패 동작도 기대 결과를 assertion한다. 의도적으로 위험한 코드가 있다고 해서 테스트를 skip하거나 assertion을 약화하지 않는다.
- DB 테스트에 테스트 전체를 감싸는 `@Transactional`을 추가하지 않는다. 실제 서비스 proxy와 repository의 commit·rollback 경계를 확인해야 한다.
- 동시성 테스트는 명시적 동기화를 사용한다. 단일 MySQL에서의 성공을 운영 DB·다중 인스턴스·브로커 안전성의 증명으로 표현하지 않는다.
- 최종 검증: 저장소 루트에서 `python3 integration-fixtures/spring-risk-sample/scripts/verify_mysql.py`. 이 명령은 전용 임시 MySQL을 사용해 샘플과 본체의 전체 빌드 및 증거 연결 검증을 실행한다. 기존 DB에는 연결하지 않는다.
- 별도 프로세스의 최종 로컬 서비스 실행 검증은 공통 문서의 7단계 규칙을 따른다. `verify_mysql.py`는 opt-in loopback HTTP 진입점을 별도 JVM에서 실행하고 응답·독립 MySQL 조회·종료를 확인한다. Spring context 테스트와 실제 서비스 실행 결과를 구분한다.
- `verify_mysql.py --baseline <변경 전 snapshot>`으로 기존 기준선을 보존해 비교한다. 기본 실행의 기준선은 현재 소스의 검증 시작 상태이며 변경 전 기록을 대신하지 않는다. 의도된 HIGH Gap 등 미해결 근거는 `workflow-completion.json`의 NEEDS_REVIEW와 종료 코드 3으로 보존한다.
- 미해결 근거는 `review-definitions.json`의 명시적 검토와 `review-requirements.md`를 대조한다. 소스·테스트·요구사항의 승인된 해시가 바뀌면 자동 재승인하지 않는다. 의도된 결함은 별도 `fixture-review-policy.json`의 정확한 ID에 한해 재현 성공으로 기록하며, staticStatus와 targetRiskStatus를 안전한 구현으로 승격하지 않는다. 실제 테스트 부족은 계속 완료를 차단한다.
