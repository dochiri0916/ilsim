# 실행 의미 검토와 테스트 근거

구조 검사는 타입·선언·의존성·지역 호출의 형태를 확인한다. DB·브로커·HTTP의 실제 동작과 업무 의미는 소비 프로젝트의 테스트와 리뷰로 확인한다. 기존 `status`/`checks`와 별도로 `conventionReview`가 이 범위를 보고한다. `REVIEW`는 미확인 의미 검증이며, 성공한 테스트가 있어도 자동으로 PASS가 되지 않는다.

## 요구 시나리오

| 대상 ID | 소비 프로젝트에서 확인할 동작 |
|---|---|
| QUERY-PAGINATION | 음수 page·허용 범위 밖 size 거부, 큰 offset의 오버플로 없음, 같은 정렬값에서도 안정적인 페이지 |
| QUERY-RESULTS | 선택·필수 접근 조건의 목록/count 일치, 조인 중복 제거, 자식 일괄 조회·순서 유지·쿼리 수로 N+1 방지 |
| PERSISTENCE-CONTRACT | 같은 Port의 기본/safe 구현에 같은 계약 테스트 적용, 제약별 오류·flush 시점·원인 보존 일치 |
| EVENT-CONTRACT | 도메인 이벤트에서 공개 payload·버전 변환, aggregate별 sequence/revision 조건, 재시도·재발행의 eventId/sequence 유지 |
| OUTBOX-DELIVERY | 업무와 Outbox의 동시 commit/rollback, 브로커 확인 전 완료 금지, Waker 유실 복구, 다중 폴러·엄격한 순서, 제한된 재시도·jitter·DLQ, 보관/CDC 전환 경계 |
| INBOX-DELIVERY | 같은 이벤트·소비자 동시 수신 1회 처리, 다른 소비자는 독립 처리, handler/후속 Outbox 원자성, commit 후 ACK, 실패를 완료로 생략하지 않음, DLQ 재처리·최대 재전달 기간 |
| WEB-CONVERSION | 실제 HTTP 입력과 UseCase 인자 일치, Result와 JSON 필드 일치, 요청 검증·업무/기술 오류 응답 |

QueryRepository·페이지 호출·같은 Port의 여러 PersistenceAdapter·공개 이벤트·Inbox/Outbox Entity·웹 Controller에서 적용 대상을 수집한다. 해당 파일별 시나리오 ID는 보고서의 `requirements[].scenarios`가 기준이다. 코드에 기술을 숨긴 위임, 외부 브로커 설정, 실제 운영 데이터와 배포 정책은 별도 코드/운영 리뷰가 필요하다.

## 실행 근거 선언

소비 Gradle 프로젝트 루트에 `.build-convention/runtime-evidence.json`을 둔다. 외부 테스트 결과 파일 경로를 입력받지 않으며 Gradle Test task의 실제 JUnit XML에서 클래스/테스트 이름을 정확히 연결한다. 멀티 모듈은 각 모듈에서 선언한다.

```json
{
  "schemaVersion": 1,
  "items": [
    {
      "id": "QUERY-RESULTS",
      "source": "src/main/java/com/example/book/adapter/out/persistence/BookQueryRepository.java",
      "scenario": "list-count-same-filters",
      "testClass": "com.example.book.adapter.out.persistence.BookQueryRepositoryTest",
      "testName": "sharesOptionalFilterAcrossListAndCount()",
      "review": "실제 DB에서 선택 조건과 필수 접근 조건을 바꿔 목록의 식별자와 count를 비교한다."
    }
  ]
}
```

`testName`은 JUnit XML의 testcase name을 그대로 사용한다. parameterized test의 표시 이름도 정확히 지정한다. 선언의 `(id, source, scenario)`는 중복하지 않는다. `review`에는 assertion이 요구사항을 어떻게 검증하는지와 DB/브로커 조건을 적는다. 문자열 선언만으로 검토 완료를 자동 판정하지 않는다.

- `EXECUTED`: 현재 성공한 Test task의 XML에 실패·오류·skip 없는 testcase가 있음.
- `MISSING`: 선언·일치하는 성공 testcase·현재 성공한 task 중 하나가 없음.
- `REVIEW`: 전체 요구사항의 의미적 타당성·운영 조건을 사람이 확인해야 함.
- `NOT_APPLIED`: 해당 기술의 적용 대상이 없음.

테스트를 실행한 사실과 테스트가 올바른 동작을 증명하는지는 다르다. 빈 테스트·Mock만으로 DB 제약을 흉내 낸 테스트·assertion과 무관한 테스트를 근거로 연결하지 않는다. 브로커 장애·동시 처리·프로세스 재시작 시나리오는 실제 의존성을 이용한 통합 테스트로, 보관 기간·CDC·DLQ 운영 절차는 설정과 재현/운영 기록을 함께 리뷰한다.

이 검토는 구조·품질 게이트의 기존 PASS/FAIL 계약을 바꾸지 않는다. `ai`는 적용 대상과 누락된 실행 근거 수를 별도로 출력하며, REVIEW가 있으면 전체 컨벤션 의미 검증이 완료됐다고 표시하지 않는다.
