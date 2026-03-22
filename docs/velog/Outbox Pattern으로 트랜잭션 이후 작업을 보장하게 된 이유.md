프로젝트를 운영하다 보면 트랜잭션 문제는 늘 "정합성"과 "성능" 사이에서 균형을 요구합니다. 이번 글은 파일 I/O가 포함된 쓰기 흐름에서, 왜 `AFTER_COMMIT`만으로는 부족했고 결국 Transactional Outbox로 넘어가게 되었는지를 다룹니다.

핵심은 단순했습니다. 트랜잭션은 짧게 유지해야 했고, 실패는 유실되면 안 됐습니다.

---

## 시작점: 트랜잭션 기준이 모호했던 코드베이스

처음 맡은 코드베이스에는 트랜잭션 기준이 명확하지 않았습니다. 어떤 쓰기 로직은 트랜잭션 안에 있었고, 어떤 로직은 그렇지 않았습니다. 일관된 기준이 없으니 장애가 발생했을 때 원인 추적이 어렵고, 정합성 보장 범위도 흐려졌습니다.

그래서 우선은 "쓰기 작업이면 트랜잭션"이라는 비교적 보수적인 기준으로 정리했습니다. 단기적으로는 안전한 선택이었습니다. 데이터 꼬임 가능성이 확실히 줄었기 때문입니다.

---

## 관찰/재현: 정합성은 좋아졌지만 커넥션 풀이 버티지 못했습니다

문제는 파일 I/O였습니다. 게시글 생성 과정에 파일 업로드/복사/삭제가 섞여 있었고, 트랜잭션 안에서 이 작업들이 실행되면 DB 커넥션을 점유한 채로 외부 I/O를 기다리게 됐습니다.

결과는 예측 가능했습니다.

- 트랜잭션 지속 시간 증가
- 커넥션 반납 지연
- 피크 트래픽에서 커넥션 풀 고갈 위험 증가

정합성은 챙겼지만 운영 안정성이 흔들렸습니다.

---

## 첫 번째 개선: AFTER_COMMIT 분리

다음으로 택한 방법은 `@TransactionalEventListener(phase = AFTER_COMMIT)`였습니다. 비즈니스 데이터 변경은 트랜잭션 안에서 끝내고, 파일 I/O는 커밋 이후로 분리했습니다.

이 조정으로 트랜잭션은 확실히 짧아졌고, 커넥션 풀 상황도 개선됐습니다. 여기까지만 보면 해결처럼 보였습니다.

하지만 운영 관점에서 중요한 질문이 남았습니다.

> "커밋 이후 작업이 실패하면 그 실패를 어떻게 보존하고, 어떻게 재처리할 것인가?"

---

## AFTER_COMMIT이 해결하지 못한 것

`AFTER_COMMIT` 이후 로직은 말 그대로 트랜잭션 밖에서 실행됩니다. 이미 데이터는 커밋된 상태고, 이후 단계의 실패는 롤백으로 복구할 수 없습니다.

더 큰 문제는 "실패 사실의 영속성"이었습니다.

- 일시 장애로 파일 복사 실패
- 프로세스 재시작 중 이벤트 유실
- 외부 스토리지 지연/오류

이런 상황에서 실패를 다시 시도하려면, "실패했음" 혹은 "아직 처리 안 됨"이라는 상태가 DB에 남아 있어야 합니다. AFTER_COMMIT 이벤트만으로는 그 기준점을 만들기 어렵습니다.

즉, 트랜잭션을 짧게 만드는 데는 성공했지만, 트랜잭션 이후 작업의 정합성과 복구 가능성은 충분히 확보하지 못했습니다.

---

## 선택 기준을 다시 세운 이유

다시 정리하면 요구사항은 두 가지였습니다.

1. 트랜잭션은 짧아야 한다
2. 커밋 이후 작업 실패는 유실되면 안 된다

모든 작업을 트랜잭션 안으로 되돌리는 것은 성능상 불가능했고, 분산 트랜잭션은 이 시스템 규모에서 운영 복잡도가 과했습니다.

남는 선택지는 결국 하나였습니다.

트랜잭션 밖에서 실행될 작업이라도, "실행되어야 한다는 사실"만큼은 트랜잭션 안에 기록합니다.

---

## 그래서 Transactional Outbox를 적용했습니다

Outbox의 핵심은 단순합니다.

- Post 저장과 OutboxEvent 저장을 하나의 트랜잭션으로 처리
- 커밋 이후 Worker가 OutboxEvent를 읽어 파일 작업 수행
- 성공/실패/재시도 상태를 DB에서 관리

이 방식의 장점은 "실패를 시스템 내부 상태로 관리할 수 있다"는 점입니다. 실패가 로그에만 남지 않고, 재처리 가능한 작업 단위로 남습니다.

실제 흐름은 아래 코드 조각으로 연결됩니다.

```java
// CreatePostFacade
public CreatePostUseCase.Output execute(CreatePostUseCase.Input input, MultipartFile file) {
    String temporaryStorageKey = generateTemporaryStorageKey(file.getOriginalFilename());
    uploadToTemporaryStorage(file, temporaryStorageKey);
    return createPostUseCase.execute(new CreatePostUseCase.Input(
            input.title(), input.content(), temporaryStorageKey,
            file.getOriginalFilename(), file.getSize(), file.getContentType()
    ));
}
```

```java
// CreatePostUseCase
@Transactional
public Output execute(Input input) {
    Post savedPost = postRepository.save(Post.create(input.title, input.content));
    String storageKey = "post/%d/%s".formatted(savedPost.getId(), input.originalFileName);
    PostFileUploadPayload payload = new PostFileUploadPayload(
            savedPost.getId(), input.temporaryFilePath, storageKey, input.fileSize, input.contentType
    );
    outboxEventRecorder.record(AggregateType.POST, savedPost.getId(), OutboxEventType.POST_FILE_UPLOAD, payload);
    return new Output(savedPost.getId());
}
```

```java
// OutboxWakeUpHintPublisher
public void publishAfterCommit() {
    if (!TransactionSynchronizationManager.isActualTransactionActive()) {
        applicationEventPublisher.publishEvent(new OutboxWakeUpHintEvent());
        return;
    }
    TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
        @Override
        public void afterCommit() {
            applicationEventPublisher.publishEvent(new OutboxWakeUpHintEvent());
        }
    });
}
```

---

## 현재 구현에서 가져간 운영 안정성 장치

이번 구현에서는 다음 장치를 함께 적용했습니다.

- `afterCommit` 기반 즉시 힌트 발행 + `@Async("outboxExecutor")` 처리
- Polling Scheduler 안전망 (`fixedDelay = 30_000`)
- 상태 머신 기반 전이 (`PENDING -> PROCESSING -> COMPLETED/FAILED`)
- 최대 재시도 초과 시 `FAILED`
- `PROCESSING` 상태가 아니면 `failed()` 불가
- Worker 실패 로그 기록
- 파일 처리 순서 고정: `copy -> exists -> delete`

즉시 실행이 실패해도 스케줄러가 회수하고, 처리 실패가 나도 상태와 재시도 횟수로 추적 가능합니다.

아래는 Worker/Handler의 실제 처리 코드입니다.

```java
// OutboxWakeUpHintListener
@Async("outboxExecutor")
@EventListener
public void onWakeUp(OutboxWakeUpHintEvent event) {
    outboxWorker.runOnce();
}
```

```java
// OutboxWorker
private void process(Long outboxEventId) {
    OutboxEventContext processingEvent = outboxStatusService.markProcessing(outboxEventId);
    if (processingEvent == null) return;

    try {
        OutboxEventHandler handler = findHandler(processingEvent.eventType());
        handler.handle(processingEvent);
        outboxStatusService.markCompleted(processingEvent.id());
    } catch (Exception e) {
        log.error("Outbox event processing failed. eventId={}, eventType={}",
                processingEvent.id(), processingEvent.eventType(), e);
        outboxStatusService.markFailed(processingEvent.id(), 5);
    }
}
```

```java
// PostFileUploadOutboxHandler
@Transactional
public void handle(OutboxEventContext eventContext) {
    PostFileUploadPayload payload = outboxPayloadSerializer.deserialize(
            eventContext.payload(), PostFileUploadPayload.class
    );
    fileStoragePort.copy(payload.temporaryFilePath(), payload.storageKey());
    if (!fileStoragePort.exists(payload.storageKey())) {
        throw new IllegalStateException("Failed to upload file to storage: " + payload.storageKey());
    }
    fileStoragePort.delete(payload.temporaryFilePath());
    postFileRepository.save(PostFile.create(
            payload.postId(), payload.storageKey(), payload.fileSize(), payload.contentType()
    ));
}
```

그리고 상태 전이 가드는 엔티티에서 직접 강제합니다.

```java
// OutboxEvent
public void failed(int maxRetryCount) {
    if (this.status != OutboxEventStatus.PROCESSING) {
        throw new IllegalStateException("Only PROCESSING events can be marked as failed");
    }
    this.retryCount++;
    this.status = (this.retryCount >= maxRetryCount) ? OutboxEventStatus.FAILED : OutboxEventStatus.PENDING;
}
```

---

## 트레이드오프

물론 비용이 없는 선택은 아닙니다.

- Outbox 테이블 운영 비용
- Worker/스케줄러 관리 비용
- 중복 실행 가능성을 고려한 핸들러 설계 필요

그래도 이 비용은 "실패가 유실되는 구조"를 감수하는 비용보다 예측 가능하고 통제 가능합니다.

---

## 정리

처음 문제는 트랜잭션이 너무 넓어서 생긴 커넥션 풀 이슈였습니다. AFTER_COMMIT은 성능 측면 문제를 많이 줄여줬지만, 실패를 복구 가능한 상태로 남기지는 못했습니다.

Transactional Outbox는 이 두 요구를 동시에 만족시켰습니다.

- 비즈니스 트랜잭션에서는 DB 변경만 처리해 커넥션 점유 시간을 줄였다
- 커밋 이후 작업은 Outbox 상태로 남겨, 실패해도 재시도 가능하게 만들었다

결국 핵심은 패턴 자체보다 경계 설정이었습니다. 무엇을 트랜잭션으로 보장하고, 무엇을 재시도 가능한 작업으로 남길지 결정하는 기준이 시스템 안정성을 만듭니다.

---

## 소스 코드

[outbox-pattern](https://github.com/dochiri0916/outbox-pattern)