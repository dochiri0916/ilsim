# 변경 전후 회귀 검증과 완료 판정

이 절차는 `CODE_CHANGE`에서 명시적으로 승인된 로컬 Gradle 프로젝트에 적용한다. `SPEC_ONLY`와 `SPEC_CODE_REVIEW`는 프로젝트 파일 탐색·해시 계산·테스트·빌드·서비스 실행 전에 거부한다. 요구사항 JSON은 기존 `requirements` CLI의 JSON Schema 검증을 통과해야 한다. 명시적으로 선택하는 실행 경로이며, 기존 `requirements --actions IMPLEMENT` 등 모든 독립 명령에 자동으로 붙는 sandbox는 아니다.

## 1. 변경 전 기준선

새 테스트 작성이나 구현 전에 영향받는 기존 기능과 `Class#method` 테스트를 직접 연결한다. 전이적인 호출자·공유 DB 영향과 테스트 수준은 자동 추론하지 않는다. 중요한 기존 동작에 테스트 공백이 있으면 production 변경 없이 보완한 후 기준선을 다시 만든다. 기준선 실패를 새 변경의 성공으로 처리하지 않는다.

다음 형식의 `plan.json`을 준비한다. `project`에는 실제 승인된 절대 경로를 사용한다. `database`는 실제 실행한 MySQL 버전이며 환경변수·URL·암호를 넣지 않는다. DB가 불필요하면 `NOT_APPLICABLE`로 기록한다.

```json
{
  "mode": "CODE_CHANGE",
  "project": "/absolute/approved/project",
  "approvedActions": ["BASELINE", "REGRESSION", "LOCAL_SERVICE", "COMPLETE"],
  "database": "MySQL 8.4.8",
  "features": {"existing.discount": ["example.PricePolicyTests#appliesDiscountAtTheExactBoundary"]},
  "newFeatures": {
    "new.create": {
      "domain": ["example.CreatePolicyTests#rejectsInvalidQuantity"],
      "mysql": ["example.DatabaseTests#rollsBackAfterFailure"],
      "mysqlRequired": true
    }
  }
}
```

아래 명령은 저장소 루트에서 실행한다. `requirements.json`은 이 변경의 schema-valid `CODE_CHANGE` 입력이다. 분석기 JAR를 먼저 준비해 둔다. 대상 프로젝트의 MySQL 연결 환경변수는 승인된 전용 DB에 맞춰 외부에서 설정한다.

```sh
python3 scripts/regression_workflow.py baseline \
  --plan plan.json --requirements requirements.json \
  --analyzer build/libs/code-risk-analyzer-0.0.1-SNAPSHOT.jar \
  --output /absolute/approved/project/build/before.json
```

실제 실행은 대상 루트의 `./gradlew cleanTest build --rerun-tasks --no-daemon`이다. 기존 결과 재사용을 막고 전체 테스트·빌드를 수행한다. 기준선은 덮어쓰지 않는다. 기능 매핑, 개별 JUnit 결과, 소스·테스트·빌드 설정 SHA-256, JUnit XML 및 JAR 해시, Java·OS·Gradle wrapper 식별 정보, DB 버전, 명령과 시간을 저장한다. 인증정보와 Gradle 원문 출력은 저장하지 않는다. 실행 중 소스가 바뀌면 실패한다.

## 2. 테스트 작성·구현·변경 후 비교

테스트 작성 단계에서는 테스트 코드만 작성한다. production 코드, 기존 동작 코드, 신규 동작 코드와 production stub을 작성하거나 수정하지 않는다. 구현은 그 다음 별도 단계에서 수행한다.

```sh
python3 scripts/regression_workflow.py compare \
  --plan plan.json --requirements requirements.json \
  --analyzer build/libs/code-risk-analyzer-0.0.1-SNAPSHOT.jar \
  --baseline /absolute/approved/project/build/before.json \
  --output /absolute/approved/project/build/after.json
```

같은 명령으로 전체 테스트를 재실행한다. 기준선의 모든 테스트 identity가 다시 성공해야 한다. 누락·실패·skip, 신규 테스트 실패, 빌드 실패는 `FAILED`다. 환경·명령·기능 매핑이 다르면 `NEEDS_REVIEW`다. 신규 기능에는 도메인 테스트가 필요하며 `mysqlRequired=true`인 경우 지정한 MySQL 통합 테스트와 DB 환경 기록도 필요하다. 기존 정책을 그대로 사용하는 adapter 변경은 그 정책의 기존 도메인 테스트를 연결할 수 있다. 테스트 이름·DB 버전 기록만으로 적정성이 증명되는 것은 아니므로 실제 assertion과 DB 사용 근거도 검토한다.

기존 테스트 소스·리소스와 빌드 설정이 바뀌면 assertion 약화 여부를 추측하지 않고 모두 검토 대상으로 남긴다. 삭제나 수정 근거가 있으면 별도 `approvals.json`을 `--approvals`로 전달한다.

```json
[
  {
    "path": "src/test/java/example/PricePolicyTests.java",
    "beforeSha256": "이전 파일의 SHA-256",
    "afterSha256": "변경 파일의 SHA-256",
    "basis": "SPEC_CHANGE",
    "reference": "승인된 정의서 v2 §3",
    "reviewer": "검토자",
    "reason": "요구사항에서 변경한 수량 경계와 기대값 반영"
  }
]
```

허용 근거는 `SPEC_CHANGE` 또는 명확히 잘못된 테스트에 대한 `INCORRECT_TEST`다. 이전·이후 해시와 검토 기록이 맞아야 변경 근거로 인정한다. 이것은 검토자 진술이지 assertion 의미의 자동 증명이 아니다. 근거가 있어도 실행 skip·실패·기존 test identity 누락은 실패로 남긴다. 의도적으로 삭제·이름 변경한 계약은 새 기준선 수립 전 별도 검토가 필요하다.

## 3. 실제 서비스와 최종 완료

패키징한 서비스는 별도 프로세스에서 실행한다. 실제 준비 상태, 기존 정책의 경계 결과, 정상·중복·동시성·실패 후 DB 상태와 재시도, 프로세스 종료를 확인한다. 프로젝트별 실제 진입점과 DB 검증 adapter가 필요하다.

이 저장소의 adapter는 [verify_service.py](../../integration-fixtures/spring-risk-sample/scripts/verify_service.py)다. JDK HTTP 서버로 `127.0.0.1`의 임의 포트에 fixture를 띄워 실제 Spring proxy를 호출한다. HTTP 응답과 전용 MySQL에서의 독립 `SELECT COUNT(*)`를 모두 검사하고, 실패해도 이 실행에서 시작한 JVM만 종료한다. 다른 프로젝트의 실행 명령이나 endpoint를 자동 추측하지 않는다.

`service.json`에는 `status`, 최종 소스 `sources` 해시 맵, 실행 JAR의 `jarSha256`, `scenarios`의 `normal`·`duplicate`·`concurrency`·`rollback` 결과, `processStopped`를 기록한다. fixture가 생성하는 `build/service-verification.json`이 예시다. `open-evidence.json`에는 최종 정적 분석의 미해결 위험·Gap·UNKNOWN 및 수동 검토 질문을 문자열 배열로 기록한다. 미해결 항목이 없음을 검토한 경우에만 빈 배열을 사용한다.

```sh
python3 scripts/regression_workflow.py complete \
  --plan plan.json --requirements requirements.json \
  --analyzer build/libs/code-risk-analyzer-0.0.1-SNAPSHOT.jar \
  --baseline /absolute/approved/project/build/before.json \
  --service service.json --open-evidence open-evidence.json \
  --output /absolute/approved/project/build/completion.json
```

완료 직전에 전체 테스트·빌드를 다시 실행하고 기준선 비교, 서비스 소스·JAR 일치, 종료, 미해결 근거를 모은다. 검증 실패는 `FAILED`, 미해결 근거는 `NEEDS_REVIEW`다. 이 CLI는 서비스를 직접 실행하지 않으므로 `--service` 결과는 항상 미해결 근거로 남아 최선이 `NEEDS_REVIEW`다. `COMPLETED`가 필요하면 서비스 adapter를 실행하는 `code_change_workflow.py`를 쓴다. 종료 코드는 각각 1, 3, 0이며 입력·계획 거부는 2다. 기존 `requirements` 출력의 action 실행 완료와 이 완료 판정을 구분한다.

## 지원 범위

미해결 항목을 테스트 부족·정적 의미 확인 한계·의도된 fixture 결함으로 분류하고 검토 기록을 연결하려면
[미해결 근거 분류와 검토 기록](evidence-review.md)을 따른다. 검증 실행의 성공과 코드 위험 해소를 별도로 보고하며, 승인된 fixture 재현 예외를 일반 프로젝트에 적용하지 않는다.

Python 3.9 이상과 POSIX 환경, 루트 `test` task의 JUnit XML을 사용하는 단일 Gradle 프로젝트를 지원한다. 다중 모듈·별도 통합 테스트 task·커스텀 source set은 실행·수집 adapter를 확장해야 한다. 기본 소스 수집은 `src`, Gradle 설정·wrapper, `buildSrc`, `build-logic`, `scripts`다. 테스트·빌드 변경은 보수적으로 보호하며 source symlink는 거부한다. 프로젝트 단위 advisory lock으로 이 runner의 동시 실행을 막지만 외부 편집기·다른 명령까지 막지는 않는다.

계획·기준선·검토 기록·서비스 증거는 승인된 호출자의 로컬 자료다. 서명된 권한 증명이나 변조 방지 저장소는 아니다. 대상 Gradle의 전체 빌드 로직이 실행되므로 승인된 프로젝트에만 사용한다. 자동 영향 분석, assertion 동등성, 실제 DB 환경의 일반 증명, 모든 위험 해소나 운영 안전성을 보장하지 않는다. PR 생성·제출은 수행하지 않는다.
