# 패키지 구조

루트는 `src/main/java/com/dochiri/tooltestlab`이며 BC와 `global`을 둔다. 단일 애그리거트는 BC 바로 아래, 여러 애그리거트는 각 애그리거트 아래에 `domain`·`application`·`adapter`를 둔다. 주요 패키지는 다음과 같다.

```text
tooltestlab/
├── member/
├── cart/
├── order/
├── review/
├── catalog/
│   ├── category/
│   └── product/
└── global/                 # 공통 VO·예외·설정·기술 기반
    ├── application/exception/  # 공통 애플리케이션 예외(RepositoryOperationException)
    ├── configuration/      # 공통 설정(JPA Auditing·OpenAPI·Time·Querydsl)과 properties/
    ├── domain/
    │   ├── vo/             # 공통 VO(Money·Quantity)
    │   ├── exception/      # 공통 VO 예외(GlobalDomainException 등)
    │   └── outbox/         # 공통 Outbox 모델
    ├── exception/          # 공통 예외 처리기·ProblemDetails
    ├── messaging/          # IntegrationEvent(공통 마커)·MessageBroker·MessageConsumer·MessageEnvelope·OutboxRelay·로컬 전송
    │   └── persistence/    # OutboxEntry·InboxEntry·OutboxStore·OutboxDispatcher·OutboxCleanup·InboxProcessor
    └── persistence/        # BaseEntity·RepositoryExceptionTranslator
```

Member·Cart·Order·Review 바로 아래, Catalog는 Category·Product 아래에 다음 계층을 둔다. 필요한 하위 패키지만 만든다.

```text
domain/                     # 도메인 모델
├── vo/                     # VO·상태 enum
├── event/                  # 도메인 이벤트
├── outbox/                 # BC별 Outbox 모델
└── exception/              # 도메인 예외
application/
├── {Bc}EventMapper.java        # 도메인 이벤트 → 공개 통합 이벤트 변환(BC 단위, 예: CatalogEventMapper)
├── {Domain}ResultMapper.java   # 도메인 객체 → Result 변환(필요한 애그리거트만)
├── port/in/                # 입력 계약
│   ├── usecase/            # 유스케이스 인터페이스
│   ├── command/            # 상태 변경 요청 입력
│   ├── result/             # 유스케이스 실행 결과
│   └── query/              # 조회 조건 입력
├── port/out/               # 저장소·외부 기능 포트
│   ├── {Domain}RepositoryPort.java   # 애그리거트 단위 저장소 포트
│   ├── IntegrationEventPort.java     # 공개 통합 이벤트 발행 포트
│   ├── {Domain}Page.java             # 페이지 조회 결과(필요한 경우)
│   ├── {Domain}SearchCondition.java  # 내부 검색 조건 record
│   └── event/              # 전송 기술에 독립적인 공개 통합 이벤트
├── service/                # 유스케이스 구현
└── exception/              # 애플리케이션 예외
adapter/
├── in/web/                 # Controller·Request·Response·{Xxx}ExceptionHandler(하위 exception 패키지를 만들지 않는다)
├── in/messaging/           # 통합 이벤트 수신
├── in/bootstrap/           # 초기화
├── out/persistence/        # Entity·JpaRepository·Mapper·저장소 어댑터
│   ├── {Domain}QueryRepository.java  # Querydsl 조회 구현
│   ├── {Purpose}Row.java    # 영속성 내부 조회 DTO(예: ProductRow)
│   ├── outbox/             # BC별 발행 대기 이벤트 저장
│   ├── inbox/              # BC별 수신 이벤트 처리 기록
│   └── projection/         # 다른 BC 이벤트로 갱신하는 로컬 조회 모델
├── out/messaging/          # 통합 이벤트 발행
└── out/security/           # 암호화 등 보안 기능 구현
```

- 입력 계약은 `port.in` 바로 아래에 두지 않고 역할별로 분리한다. UseCase는 `port.in.usecase`, Command는 `port.in.command`, Result는 `port.in.result`, Query는 `port.in.query`에 둔다. `CategorySummary` 같은 조회 결과 DTO도 `port.in.result`에 둔다.
- Request·Response는 `adapter.in.web.request`, `adapter.in.web.response`에 둔다.
- 내부 검색 조건은 `{Domain}SearchCondition`, Querydsl 조회 구현은 `{Domain}QueryRepository`, 영속성 내부 DTO는 `{Purpose}Row`로 이름을 짓는다.
- 이벤트 계약과 전송 경계는 [BC 이벤트](bounded-context-events.md), 트랜잭션·멱등성·재시도는 [Outbox/Inbox](outbox-inbox-conventions.md) 컨벤션을 따른다.
- 계층별 규칙은 [도메인](domain-conventions.md), [애플리케이션](application-conventions.md), [어댑터](adapter-conventions.md), [영속성](persistence-conventions.md), [Querydsl](querydsl-conventions.md) 컨벤션을 따른다.
