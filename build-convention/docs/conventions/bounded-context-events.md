# BC 간 이벤트 통합

- 내부 도메인 이벤트와 공개 통합 이벤트를 구분한다. 공개 계약은 원시 값·불변 목록으로 작성하고, 이벤트 타입과 버전은 `catalog.product-changed.v1`처럼 명시한다. 공개 통합 이벤트는 일급 컬렉션 강제 대상에서 제외하며, 생성 시 목록을 방어적으로 복사하고 도메인·애플리케이션의 일급 컬렉션 타입을 계약에 노출하지 않는다.
- 도메인 이벤트는 애플리케이션의 EventMapper에서 공개 계약으로 변환하고 `IntegrationEventPort`로 발행한다.
- `MessageEnvelope`는 `eventId`, `source`, `eventType`, `aggregateId`, `sequence`, `occurredAt`, `payload`를 담는다. Java 클래스 이름을 전송 계약으로 사용하지 않는다.
- `sequence`는 발신 BC의 Outbox 저장 계층이 최초 저장 시 부여하는 양수이며, 같은 `source`의 Outbox 전체에서 증가하고 중복되지 않는다. 번호의 연속성은 요구하지 않는다. 최초 전송 전에 값을 확정하고 재시도·재발행·DLQ 재처리에서도 유지한다. BC 안에서 Outbox를 여러 테이블로 나누면 발급 범위를 공유한다. `sequence`를 Kafka offset으로 대체하지 않는다.
- 조회 모델은 `(source, aggregateId)`별 마지막 적용 `sequence`를 저장하고, 더 큰 값만 적용한다. 다른 발신 BC·애그리거트의 값을 서로 비교하지 않는다. 로컬 조회 행의 키가 `memberId`처럼 애그리거트 ID와 다르면 발신 `aggregateId`도 함께 보관해 비교 범위를 구분한다.
- `revision`은 애그리거트 상태 변경 시 도메인에서 증가시키는 상태 버전이며 같은 애그리거트 안에서만 비교한다. revision이 있는 조회 모델은 `sequence > lastSequence`와 `revision > lastRevision`을 모두 만족할 때 갱신하고, 같은 revision의 재발행은 상태를 덮어쓰지 않는다. 최초 이벤트는 초기화에 사용하며, 애그리거트 교체는 별도의 업무 규칙으로 처리한다.
- `sequence`는 저장 번호이므로 그 자체로 업무 상태 변경 순서나 트랜잭션 커밋 순서를 보장하지 않는다. 같은 애그리거트의 저장 번호가 상태 변경 순서와 일치하도록 동시 변경을 제어한다. 엄격한 처리 순서가 필요한 업무는 [Outbox/Inbox](outbox-inbox-conventions.md)의 직렬화 규칙을 함께 적용한다.
- 현재 구현은 BC별 단일 Outbox 테이블의 내부 PK를 `sequence`로 사용한다. 위 규칙은 적용 기준이며, 다중 테이블 발급·애그리거트 비교 범위·동시 변경 제어까지 구현이 완료되었다는 의미는 아니다. 전송 기술 교체나 Outbox 재저장 시에도 최초 `sequence`를 유지한다.
- 현재 로컬 전송은 Kafka 전환 시 `MessageBroker` 구현과 `MessageConsumer`를 호출하는 수신 전송 어댑터로 교체한다. 이벤트 계약·유스케이스·Outbox·Inbox는 유지한다.
- Kafka 전환 시 `aggregateId`를 메시지 key로 사용하고 BC별 consumer group을 둔다. Kafka API·설정은 전송 어댑터에 두고 BC별 이벤트 처리 로직과 분리한다.
- 공개 통합 이벤트는 `application.port.out.event`에 두며 `Port` 접미사를 붙이지 않는다. 공통 마커 `global.messaging.IntegrationEvent`(`eventType`·`aggregateId`)를 구현할 수 있으며, 애플리케이션이 이 마커 외의 전송 기술 타입에 의존하지 않는다.

전달 보장·재시도·완료 판정·트랜잭션·소비자별 멱등성·ACK/offset 커밋·보관 규칙은 [Outbox/Inbox](outbox-inbox-conventions.md) 컨벤션을 따른다.

패키지 배치는 [패키지 구조](package-structure.md), 계층별 책임은 [애플리케이션](application-conventions.md)·[어댑터](adapter-conventions.md) 컨벤션을 따른다.
