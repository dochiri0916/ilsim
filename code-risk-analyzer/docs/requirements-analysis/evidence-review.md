# 미해결 근거 분류와 검토 기록

기준선부터 작성·검증·검토까지 순서를 강제하려면 [CODE_CHANGE 단계 실행 제어](code-change-workflow.md)를 사용한다. 문서·소스·실행·검토 파일 변경 시 이전 수락을 무효화하고, 일반 프로젝트의 미해결 위험은 fixture 예외 없이 차단한다.

`CODE_CHANGE`의 최종 완료 판정에 적용한다. `SPEC_ONLY`·`SPEC_CODE_REVIEW`는 프로젝트 탐색·검토 근거의 코드 대조·실행 검증 전에 거부한다. 문서 분석 결과를 이 완료 판정에 자동으로 연결하지 않는다.

## 분류

| category | 의미 | 완료 처리 |
|---|---|---|
| TEST_GAP | 검토자가 실제 동작 검증 부족을 확인 | 계속 차단. 실행 성공만으로 충분하다고 판단하지 않음 |
| STATIC_LIMITATION | 실제 테스트의 assertion과 요구사항을 검토했지만 분석기는 의미를 확정하지 못함 | 테스트 Obligation은 근거가 유효하면 검토 완료로 인정. 정적 MISSING/PARTIAL/UNKNOWN은 보존 |
| INTENTIONAL_DEFECT | 검증용 fixture의 의도된 결함을 assertion으로 재현 | 별도 승인 fixture 정책의 정확한 대상만 재현 성공으로 인정. 코드 위험은 미해결로 보존 |
| UNCLASSIFIED | 검토 기록이 없음 | 추가 검토 필요. 테스트 존재만으로 임의 분류하지 않음 |

일반 프로젝트의 production 위험은 테스트가 성공하거나 STATIC_LIMITATION 검토 기록이 있어도 계속 차단한다. 현재 기능은 위험을 해소했다고 판단하는 기능이 아니다. 위험 수정 후 다시 분석·검증해야 한다.

## 검토 bundle

`scripts/evidence_review.py`가 원본 scan의 모든 미해결 Obligation과 production finding을 수집한다. 리뷰 입력에서 항목을 빼도 미분류 차단 항목으로 남는다. 중복 ID·없는 항목·잘못된 분류를 거부한다. 원본 scan은 수정하지 않는다.

```json
{
  "version": 1,
  "snapshotSha256": "최종 실행 snapshot의 canonical JSON SHA-256",
  "reportSha256": "원본 scan JSON 파일의 SHA-256",
  "records": [
    {
      "id": "OBLIGATION-...",
      "category": "STATIC_LIMITATION",
      "decision": "VERIFIED",
      "reviewer": "검토자",
      "reason": "요구사항의 결과와 실제 assertion을 대조한 근거",
      "sourceSha256": "production 소스 SHA-256",
      "requirement": {
        "id": "REQ-1", "path": "docs/definition.md", "sha256": "문서 SHA-256",
        "line": 12, "text": "해당 줄의 요구사항 원문"
      },
      "tests": [
        {
          "test": "example.ServiceTests#createsOneRow",
          "path": "src/test/java/example/ServiceTests.java", "sha256": "테스트 소스 SHA-256",
          "methodLine": 25,
          "assertions": [{"line": 29, "text": "assertEquals(1, repository.count());"}],
          "junit": {"file": "TEST-example.ServiceTests.xml", "sha256": "실행 XML SHA-256"}
        }
      ]
    }
  ]
}
```

`snapshotSha256`는 `fingerprint(snapshot)`으로 계산한다. record의 `sourceSha256`는 scan primary location 파일과 실행 snapshot·현재 파일 모두에 일치해야 한다. 요구사항의 파일 해시·원문 줄, 테스트 소스 해시·선택한 클래스/메서드 내부의 실행 가능한 assertion 줄, 해당 클래스의 JUnit 해시와 PASS 결과도 대조한다. 다른 메서드의 assertion·다른 클래스의 XML·skip·오래된 해시·symlink·경로 우회는 인정하지 않는다. assertion이 요구사항을 충분히 검증하는지는 검토자의 판단이며 문자열 확인의 자동 증명이 아니다.

`TEST_GAP`에는 `decision=INSUFFICIENT`와 부족한 동작의 요구사항·소스·이유를 기록한다. 테스트 근거를 억지로 넣어 완료시키지 않는다. `STATIC_LIMITATION`은 VERIFIED, `INTENTIONAL_DEFECT`는 REPRODUCED를 사용한다. production finding ID는 `risk:<ruleId>:<상대 파일>:<startLine>`이다.

## 최종 실행을 유지한 검토 완료

먼저 `compare`로 최종 전체 테스트·빌드를 실제 재실행하고 서비스 검증과 최종 scan을 수행한다. 그 실행 snapshot을 기준으로 검토 bundle을 만든다. 검토 후 테스트를 다시 실행하면 XML과 실행 fingerprint가 바뀌므로 새 결과에 근거를 다시 연결해야 한다.

```sh
python3 scripts/regression_workflow.py complete \
  --plan plan.json --requirements requirements.json \
  --analyzer build/libs/code-risk-analyzer-0.0.1-SNAPSHOT.jar \
  --baseline /absolute/project/build/before.json \
  --verified-snapshot /absolute/project/build/after.json \
  --static-report build/scan.json --evidence-review reviews.json \
  --service service.json --open-evidence other-open-evidence.json \
  --output /absolute/project/build/completion.json
```

`--verified-snapshot`는 `compare`의 wrapper 또는 원본 snapshot이다. 이 경로에서는 빌드를 다시 실행하는 대신 현재 전체 소스·환경·JUnit 파일/개별 결과·JAR 해시가 마지막 실제 재실행과 같은지 검사한다. 바뀌면 거부한다. `--static-report`는 대상 프로젝트 기준 상대 경로다. 추가 수동 질문·분석 진단은 `--open-evidence`에 별도로 남긴다. 새 옵션은 complete에서만 함께 사용한다. 기존 옵션만 사용하는 절차의 동작도 유지한다.

## fixture 정책과 완료 상태

`--fixture-policy`는 검토 bundle과 별개의 명시적 승인 입력이다. `version=1`, `kind=FIXTURE`, `project`, `allowedItems`를 갖는다. project는 정책 파일 위치에 대한 상대 경로 또는 절대 경로이며 실행 대상과 같아야 한다. 허용 항목 각각은 `id`, `sourceSha256`, `requirementSha256`가 정확히 일치해야 한다. bundle의 분류 선언만으로 일반 프로젝트에 fixture 예외를 부여하지 않는다. 정책도 호출자의 승인 자료이지 서명된 권한 증명은 아니다.

완료 보고서는 다음을 분리한다.

- `verificationStatus`: 회귀·빌드·실제 서비스와 종료 검증의 실행 상태.
- `evidenceReviewStatus`: 실제 테스트 부족·미분류·유효하지 않은 근거가 남았는지.
- `targetRiskStatus`: 보고된 production 위험이 있는 경우 UNRESOLVED. 위험이 없으면 NO_REPORTED_RISKS이며 전체 안전성을 의미하지 않는다.
- `status`: 전체 완료 여부. 검증 실패는 FAILED, 차단 근거가 남으면 NEEDS_REVIEW, 선언된 검증 범위가 충족되면 COMPLETED.

fixture의 승인된 결함이 모두 재현되고 다른 검증 공백이 없으면 COMPLETED여도 targetRiskStatus는 UNRESOLVED다. 일반 프로젝트의 위험이나 fixture의 실제 테스트 부족을 이 예외로 통과시키지 않는다. 원본 staticStatus와 reviewer decision·acceptance·근거를 `evidenceReview.items`에 별도로 저장한다.

현재 [fixture 검토 정의](../../integration-fixtures/spring-risk-sample/review-definitions.json)는 production·테스트·요구사항의 승인된 해시를 고정한다. [생성기](../../integration-fixtures/spring-risk-sample/scripts/fixture_reviews.py)는 같은 검토 의미를 새 PASS 실행에 연결하며 코드가 바뀌면 자동으로 새 해시를 승인하지 않는다. [fixture 정책](../../integration-fixtures/spring-risk-sample/fixture-review-policy.json)은 명시한 의도된 결함 5개 근거만 허용한다.

현재 분류는 TEST_GAP 0개, STATIC_LIMITATION 21개, INTENTIONAL_DEFECT 5개다.
기동 전 반복 stop·이후 기동과 실제 점유 포트의 bind 실패·반복 실패·재시도·포트 반환을 신규 테스트로 확인했다.
bind 예외 경로는 원래 정적 판정에서 COVERED가 됐고, stop의 4개 근거는 새 assertion을 검토한 STATIC_LIMITATION으로 갱신했다.
소스 줄 이동에 따른 Obligation ID 변경도 조건·요구 결과를 대조해 검토 정의에 반영했다. 의도된 결함의 예외 대상은 확장하지 않는다.

Java void 테스트 메서드의 일반 소스 형태만 assertion 위치 검증에 지원한다. text block 등 미지원 소스 형태는 추가 근거 adapter가 필요하다. 이 검토는 assertion 의미·전이적인 영향·운영 안전성을 자동 증명하지 않는다.
