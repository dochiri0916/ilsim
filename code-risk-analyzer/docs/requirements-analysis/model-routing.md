# Capability Tier 기반 모델 라우팅

`scripts/model_router.py`는 Tier와 Registry·Policy JSON으로 로컬 CLI 모델을 선택한다. Tier는 명시적으로 지정하거나 `scripts/jev_capability.py`의 JEV 판단으로 얻는다. JEV에는 모델 후보·Registry·Policy를 전달하지 않는다. 추가 Python dependency와 외부 ai-router 디렉터리는 사용하지 않는다.

정책에 지정된 모델 선택, JEV의 Tier·위험도 판단, 제한된 생성 오류 escalation과 [검증 실패의 구현 수정 루프](code-change-workflow.md)를 제공한다. 성공 작업당 비용은 아래 `cost_report.py`로 측정한다. 측정값을 이용한 자동 비용 최적화와 개발 모델 Golden Benchmark·Shadow·Canary 승격은 후속 범위다. 기존 `agent` 직접 선택과 수동 작성 경로도 유지한다.

## Registry

프로젝트의 `models.json` 예시:

```json
{
  "version": 1,
  "models": {
    "focused": {
      "adapter": "codex",
      "model": "gpt-6-luna",
      "status": "ACTIVE",
      "supportedTiers": ["TIER_1", "TIER_2"],
      "reasoningEfforts": ["low", "medium", "high"]
    },
    "general": {
      "adapter": "codex",
      "model": "gpt-6.1-sol",
      "status": "ACTIVE",
      "supportedTiers": ["TIER_2", "TIER_3", "TIER_4"],
      "reasoningEfforts": ["medium", "high", "xhigh"]
    }
  }
}
```

Registry 키는 정책이 참조하는 profile ID이며 실제 모델 ID와 분리한다. `adapter`는 설치된 `codex` 또는 `claude` CLI다. 실제 모델·reasoning 지원과 계정 접근 가능 여부는 운영자가 확인해 등록해야 한다. `supportedTiers` 선언이나 `ACTIVE` 상태 자체가 모델 품질을 증명하지 않는다.

재현성이 필요한 작업에는 버전이 포함된 모델 ID를 등록한다. Claude의 `sonnet`·`opus` alias는 `ANTHROPIC_DEFAULT_*_MODEL` 환경변수로 다른 모델에 연결될 수 있다. 선택 결과는 요청한 이름을 기록하므로 alias의 실제 해석은 별도로 확인해야 한다. [Claude 모델 설정](https://code.claude.com/docs/en/model-config)

상태는 `DISCOVERED`, `CANDIDATE`, `BENCHMARKED`, `SHADOW`, `CANARY`, `ACTIVE`, `DEPRECATED`, `RETIRED` 중 하나다. 현재 실행 경로에서는 `ACTIVE`만 선택한다. 상태 전환을 자동 실행하지 않는다.

## Policy

프로젝트의 `routing-policy.json` 예시:

```json
{
  "version": 1,
  "tiers": {
    "TIER_1": {"modelId": "focused", "reasoningEffort": "low"},
    "TIER_2": {
      "modelId": "focused", "reasoningEffort": "medium",
      "fallbacks": [
        {"modelId": "general", "reasoningEffort": "medium"},
        {"modelId": "general", "reasoningEffort": "high"}
      ]
    },
    "TIER_3": {
      "modelId": "general", "reasoningEffort": "medium",
      "fallbacks": [
        {"modelId": "general", "reasoningEffort": "high"},
        {"modelId": "general", "reasoningEffort": "xhigh"}
      ]
    },
    "TIER_4": {
      "modelId": "general", "reasoningEffort": "high",
      "fallbacks": [{"modelId": "general", "reasoningEffort": "xhigh"}]
    }
  }
}
```

현재 사용할 Tier만 정의할 수 있다. 모든 정의는 등록된 profile·지원 Tier·reasoning과 일치해야 하며, 생성 단계가 요구하는 Tier의 경로가 없으면 실행을 거부한다. 다른 모델이나 CLI 기본값으로 자동 대체하지 않는다. 지원 effort 문자열은 `low`, `medium`, `high`, `xhigh`, `max`이며, 해당 profile에 명시된 값만 선택한다.

`fallbacks`는 생략하거나 최대 두 항목을 지정한다. 전체 경로는 ACTIVE profile이어야 하며 같은 adapter·실제 모델을 다시 선택할 때는 effort가 엄격히 증가해야 한다. profile ID를 바꿔 같은 모델·effort를 반복할 수 없다. 자동 fallback의 `max`는 금지한다. `max`를 명시한 최초 선택은 기존 직접 정책으로 유지한다.

## Workflow 연결

[단계 실행기](code-change-workflow.md)의 기존 config에 `agent` 대신 `routing`을 지정한다. 두 설정을 동시에 제공하면 실행을 거부한다.

```json
"routing": {
  "registry": "/absolute/project/models.json",
  "policy": "/absolute/project/routing-policy.json",
  "requiredTiers": {
    "test-writing": "TIER_2",
    "implementation": "TIER_3"
  },
  "timeoutSeconds": 300
}
```

Registry·Policy는 절대 경로로 지정하며, `requiredTiers`에는 두 생성 단계의 Tier를 모두 명시한다. timeout은 기존 adapter와 동일하게 1~3600초다. 버전·필드·중복 키·비정상 JSON 값을 검증하고, 두 단계의 경로를 기준선 실행 전에 검사한다. 생성 명령은 기존 `generate-tests`와 `generate-implementation`을 그대로 사용한다.

선택한 effort는 Codex의 `-c model_reasoning_effort="..."`, Claude의 `--effort ...`로 전달한다. 직접 선택 경로에도 `reasoningEffort`를 사용할 수 있으며, 이때 `model`과 `allowedModels`를 함께 명시해야 한다. effort를 생략하면 기존 CLI 기본 동작을 유지한다.

## JEV Capability 자동 판단

`requiredTiers` 대신 다음처럼 `capability`를 설정한다. 두 선택 방식을 동시에 지정할 수 없다.

```json
"routing": {
  "registry": "/absolute/project/models.json",
  "policy": "/absolute/project/routing-policy.json",
  "capability": {
    "model": "jev-latest",
    "minConfidence": 0.8,
    "timeoutSeconds": 30
  },
  "timeoutSeconds": 300,
  "maxAttempts": 3
}
```

자동 판단에서는 가능한 결과 전체인 TIER_1~4의 경로를 기준선 실행 전에 검증한다. `capability: {}`는 위 기본값을 사용한다. JEV model은 `jev-` 이름만 허용하고, minConfidence는 0.5~1, JEV timeout은 1~120초다. 재현성이 필요하면 관측한 snapshot 이름을 명시한다.

생성 단계·검토된 요구사항·승인된 파일 범위·문서와 코드 context·수락한 테스트 연결을 한 요청에 담아 Tier와 위험도를 두 `Choice` 질문으로 판단한다. 목적지는 `https://api.typesafe.ai/v1/systemone`으로 고정하며 redirect와 환경 proxy 설정을 사용하지 않는다. **자동 판단은 해당 context를 TypeSafe로 전송하는 설정**이므로 전송 가능한 프로젝트에서 명시적으로 활성화한다. 기존 필수 검증·scope·실행 권한은 JEV 판단으로 변경하지 않는다. [TypeSafe API](https://docs.typesafe.ai/api), [Choice](https://docs.typesafe.ai/primitives/choice).

선택·분포·confidence·실제 JEV snapshot·token usage를 검증한다. `UNKNOWN`이나 낮은 confidence는 모델 호출과 변경 적용을 차단한다. 위험도 MEDIUM은 TIER_3와 confidence 0.85 이상, HIGH는 TIER_4와 confidence 0.9 이상을 코드에서 최소 기준으로 적용한다. 설정한 minConfidence가 더 높으면 그 값을 사용한다. API confidence의 반올림은 허용하되 분포에서 계산한 confidence보다 높게 수락하지 않는다. 이 초기 기준은 자체 업무 데이터로 보정해야 하며, 의미적 정확성의 증명이 아니다. [TypeSafe confidence](https://docs.typesafe.ai/confidence).

인증은 실행 환경의 `TYPESAFE_API_KEY`만 사용한다. 키·임의 endpoint를 config에 넣을 수 없으며 키를 artifact·prompt·CLI 자식 프로세스에 전달하지 않는다. 현재 터미널에서 등록한 파일을 사용하는 실행 예시는 다음과 같다.

```zsh
source "$HOME/.secrets"
python3 scripts/code_change_workflow.py --config /absolute/config.json --state /absolute/project/build/session/state.json generate-tests
```

## 제한된 생성 오류 escalation

`maxAttempts`는 한 생성 명령의 총 CLI 호출 한도이며 기본값 1, 허용 범위 1~3이다. Policy의 fallback 경로가 더 짧으면 그 끝에서 종료한다. JEV는 명령당 한 번 판단하며, 최초 Tier를 해당 명령 동안 고정한다. 명확한 출력 오류의 복귀는 코드가 결정한다.

- CLI 정상 완료가 확인된 후 JSON·구조화 응답·시나리오별 테스트 연결 계약을 위반한 `OUTPUT_CONTRACT`만 다음 경로로 재시도한다.
- 인증·잔액·CLI 부재·timeout·불명확한 provider 실패·출력 크기 초과·모호한 요구사항·범위 밖 변경은 재시도하지 않는다. JEV의 HTTP·연결·응답 오류도 자동 재시도하지 않는다.
- 실패 이력을 다음 prompt에 전달하고 매번 새 임시 workspace·CLI 세션을 만든다. 실패한 응답의 파일을 적용하지 않으며 계약을 통과한 최종 응답만 기존 writer로 적용한다.
- 각 외부 호출 직후 입력·소스 해시를 다시 검사한다. 변경을 발견하면 다음 호출과 원본 적용을 모두 차단한다.

일반적인 TIER_3 정책은 medium → high → xhigh이며 이후 검토 대기로 종료한다. 이 한도는 자동 호출에 적용되며 사용자의 명시적 명령 재호출은 별도 작업이다. 최대 CLI 대기 시간은 `maxAttempts × timeoutSeconds`이고 JEV 대기·프로세스 정리 시간이 추가된다. 실제 비용 예산은 아직 적용하지 않는다.

JUnit·빌드·정적 검증 실패는 기존 verification에서 그대로 차단한다. **그 실패를 자동으로 코드 수정·재검증하는 루프는 아직 없다.** 구현 전 예상 실패의 원인 분석과 최종 coverage·mutation 통합도 별도 후속 작업이다.

## 라우팅 근거와 무효화

생성 artifact인 `agent-test-writing.json`·`agent-implementation.json`에 기존 입력·소스·prompt 해시와 함께 다음 `routing` 필드를 기록한다.

- Router 버전, 요구 Tier, profile ID, 요청한 adapter·모델·reasoning
- 읽어서 선택에 사용한 Registry·Policy 각각의 SHA-256
- JEV 사용 시 질문/상태를 포함한 요청 SHA-256, 분포·confidence·snapshot·usage와 수락 결과
- `attempts`의 각 시도 상태·실패 종류·요청 모델/effort·prompt SHA-256

Registry·Policy의 파일 해시, 단계별 Tier·timeout 설정, Router·adapter·controller 코드도 세션 입력 근거에 포함한다. 설정이나 파일이 바뀌면 과거 검증·검토·완료를 재사용할 수 없다. 생성 중 변경은 원본 적용을 차단하며, artifact의 라우팅 결과를 수정해도 기존 artifact 해시 검사에서 거부한다. 정책 교체는 새로 검토한 세션에서 수행한다.

라우팅 기록은 요청한 선택을 나타낸다. CLI가 실제 사용한 모델 snapshot, 인증 계정, gateway endpoint나 청구 금액을 증명하지 않는다. 인증은 기존 CLI 환경을 사용하며 credential·임의 endpoint·임의 명령을 Registry나 Policy에 지정할 수 없다. API 단가를 구독 CLI의 실제 비용으로 기록하지 않는다.

후속 검증 실패 수정 루프도 인증·잔액·환경·입력·해시 오류를 구현 실패와 구분해야 한다. 후보 승격 조건은 `QualityGatePassed AND (CostImproved OR LatencyImproved)`로 정의하고, 최종 coverage·mutation 근거는 구현 후 코드에서 수집한다.

## 검증 기록 (2026-10-03)

- 관련 Python 테스트 63개와 전체 Python 테스트 98개 PASS, Java 25의 전체 Gradle build PASS. Java 테스트는 기존 233개 결과를 재사용했다.
- 실제 Codex `gpt-6.1-sol / medium`과 Claude `sonnet / medium`에서 구조화 응답 계약·요청 모델/effort 기록을 확인했다. 결과는 `build/model-routing-smoke-summary.json`이다. 전체 개발 작업의 품질이나 CLI의 실제 모델 snapshot 검증은 아니다.
- Claude 검증 호출에서는 현재 부모 프로세스에 남아 있던 `ANTHROPIC_BASE_URL`, `ANTHROPIC_AUTH_TOKEN`, `ANTHROPIC_MODEL`, `ANTHROPIC_DEFAULT_SONNET_MODEL`, `ANTHROPIC_DEFAULT_OPUS_MODEL`을 제외했다. 사용자 설정 파일과 부모 환경은 변경하지 않았다.

CLI 옵션 기준: [Codex 설정](https://learn.chatgpt.com/docs/developer-settings), [Claude CLI reference](https://code.claude.com/docs/en/cli-reference).

## JEV 연결 검증 추가 (2026-10-03)

- 관련 Python 86개와 전체 Python 121개 PASS. Java 25의 전체 Gradle build PASS이며 기존 Java 테스트 233개 결과는 UP-TO-DATE로 재사용했다. 검증 로그는 `/private/tmp/code-risk-jev-capability-build.log`다.
- 합성 입력으로 JEV `jev-1.13.0` → Tier 선택 → Registry/Policy → 실제 Codex `gpt-6.1-sol / medium` 구조화 응답을 확인했다. 명확한 주석 오탈자 작업은 TIER_1/LOW, 신뢰도가 낮은 숫자 경계 테스트 작업은 LOW_CONFIDENCE로 실행을 차단했다. 근거는 `build/jev-capability-smoke-request.json`과 `build/jev-capability-smoke-summary.json`이다.
- 코드·요구사항이 포함된 시험 호출은 자동 승인 검토에서 payload·목적지의 명시적 승인 부족으로 차단되어, 실제 프로젝트 코드를 보내지 않는 합성 설명으로 실제 연결을 검증했다. 프로젝트 업무의 JEV 판단 정확성·모델 품질·완료를 검증한 결과는 아니다.
- 생성 오류 escalation, 기존 검증 보호와 JEV 실패·신뢰도·해시 변경 차단은 로컬 계약 테스트로 검증한다. 위 실제 시험은 CLI 오류를 강제로 발생시킨 실계정 escalation 시험이 아니다.

## 사용자 승인 후 실제 코드 API 시험

사용자가 코드·요구사항의 API 시험을 승인한 후 실제 저장소 파일을 TypeSafe에 전송했다. 원본은 변경하지 않았으며 두 요청 모두 `jev-1.13.0`의 분포·confidence·usage 계약을 통과했다.

| 입력 | 선택된 원시 Tier / 위험도 | 수락에 사용하는 confidence | 고위험 기준 | 결과 |
|---|---|---|---|---|
| 검증 실행기·회귀 비교 코드와 다음 수정 루프 요구사항 | TIER_3 / HIGH | 0.35 | 0.9 | NEEDS_REVIEW |
| MySQL fixture의 SafeService·Repository·동시성/rollback 계약 | TIER_4 / HIGH | 0.65 | 0.9 | NEEDS_REVIEW |

두 건 모두 낮은 confidence에서 라우팅과 후속 모델 실행을 차단했다. 이는 API 인증·응답 처리와 보수적 실행 차단을 확인한 결과이며, 해당 개발 작업의 수행 성공이나 판단 정확성 검증은 아니다. 실제 프로젝트 코드를 사용한 입력·파일 해시·응답은 `build/jev-approved-api-test/`에 기록했다. 입력 token은 합계 26,286개, 출력 token은 합계 226개다.

이 결과를 기준으로 실제 작업별 기준 데이터를 모아 질문의 Tier 구분, 단계에 필요한 context 범위와 confidence 기준을 보정한 뒤 검증 실패의 자동 수정 루프를 연결한다. 두 시험 결과만으로 기준값을 변경하지 않는다.

## JEV 질문·입력·confidence 보정

기존 Choice 계약은 `questionVersion: 1`로 유지한다. `questionVersion: 2`에서는 난이도와 위험도를 순서 있는 Score로 분리하고, 입력의 판단 가능성을 별도 SUFFICIENT/UNKNOWN Choice로 판단한다. 기본값은 기존 버전이며 새 버전은 설정으로 선택한다. [TypeSafe Score](https://docs.typesafe.ai/primitives/score)와 [confidence 계산](https://docs.typesafe.ai/confidence)의 응답 계약을 사용한다.

```json
"capability": {
  "model": "jev-latest",
  "questionVersion": 2,
  "minConfidence": 0.8,
  "timeoutSeconds": 30,
  "calibration": "/absolute/repository/build/jev-calibration-current/summary.json"
}
```

JEV에는 행동 계약·시나리오·도메인·효과 조건과 직접 코드 context를 제공한다. mode·review 판정·mappings 등 workflow 관리 정보와 AGENTS·Gradle 작성 규칙을 제외한다. 코드 생성 Agent에는 기존 전체 context를 계속 전달한다. 요구사항 행동 계약은 보존하고 코드 context만 파일별 4,000자, 전체 24,000자로 제한하며 누락 문자 수와 원본 해시를 표시한다. 원본 입력의 2MB 한도도 먼저 검사한다.

실제 API는 probability, score, confidence를 독립적으로 반올림한다. 표시된 probability의 가능한 unit-sum 분포를 계산해 응답 일관성을 검증하며, 수락에는 가능한 confidence의 최저값을 사용한다. 기본 임계값은 LOW 0.8, MEDIUM 최소 0.85, HIGH 최소 0.9를 유지한다. Score의 최빈 수준을 Tier/위험도에 연결하고 기존 위험도 Tier floor를 적용한다. HIGH가 충분히 확실하면 모든 원시 Tier가 TIER_4로 연결되므로 최종 Tier의 불확실성은 없다. MEDIUM도 floor로 동일해진 수준의 분포를 합쳐 최종 Tier confidence를 계산한다. 위험도·sufficiency의 불확실성은 계속 차단하며 원시 confidence도 보존한다. 입력 부족과 낮은 최종 판단 confidence를 모델 교체나 반복 호출로 우회하지 않는다.

[실제 저장소 작업 corpus](capability-benchmark.json)는 8개 보정 작업과 4개 holdout을 분리한다. comment, 순수 계산 테스트, HTTP 연계, MySQL rollback, SQL 보안, 완료 근거, 동시성 및 불명확 요구사항을 포함한다. 실제 소스와 Python symbol을 읽어 요청·원본·질문·모델 snapshot·usage를 기록한다.

```sh
python3 scripts/calibrate_jev.py \
  --project /absolute/repository \
  --corpus /absolute/repository/docs/requirements-analysis/capability-benchmark.json \
  --output /absolute/repository/build/jev-calibration
```

실행 환경의 `TYPESAFE_API_KEY`를 사용해 두 질문 버전을 실제 API에서 평가한다. 키와 원본 응답·provider 로그를 receipt에 저장하지 않는다. 보정·holdout 각각에서 수락한 판단의 precision 1.0, 수락 비율 최소 0.5, 고위험 과소 분류와 불명확 작업 수락 0건을 요구한다. 모델 confidence를 정답 확률로 해석하지 않는다. 동일한 고정 요청의 2~3개 완전한 평가 trial을 `combine_reports`로 합쳐 변동성도 측정할 수 있다. 모든 관측을 포함하며 실패한 관측을 제거하지 않는다. 운영 요청에는 이를 재시도 정책으로 적용하지 않는다.

선택한 calibration receipt가 현재 질문·의미 입력 버전·JEV 도구·요청 context·corpus와 일치하지 않거나 기준을 충족하지 않으면 실행을 거부한다. workflow 입력에 receipt와 도구 해시를 포함한다. 운영 호출이 평가와 다른 JEV snapshot을 반환하면 CLI 실행을 차단한다. 초기 12개 작업은 운영 성공률이나 개발 모델의 200개 Golden Benchmark를 증명하는 데이터가 아니다.

최종 의미 입력·위험 기준으로 `jev-1.13.0`을 12개 고정 작업에서 두 번 평가했다. 보정 split은 알려진 판단 14회 중 12회 수락(85.7%), holdout은 알려진 판단 6회 모두 수락했다. 수락한 18회 판단의 Tier·위험도 오판은 0건이며, 불명확한 4회 판단은 모두 차단됐다. 두 평가의 모든 관측을 `build/jev-calibration-current/summary.json`에 포함했다. 낮은 confidence로 중단된 나머지 작업을 자동 재요청하지 않는다. 이 소규모 결과를 일반 업무의 정확도 보장으로 해석하지 않는다.

## 성공 작업당 비용 측정

`agent_runner.py`는 CLI 호출마다 소요 시간과 토큰 사용량 숫자만 기록한다. Codex는 `turn.completed`의 `usage`, Claude는 결과 envelope의 `usage`와 `total_cost_usd`를 읽는다. 입력 토큰은 캐시 읽기·생성을 포함한 값이고 `cachedInputTokens`는 그중 캐시에서 읽은 양이다. provider 응답 본문은 저장하지 않는다. 각 시도 요약(`attempts`)에 provider·model·reasoning effort·usage·소요 시간이 함께 남는다.

```bash
python3 scripts/cost_report.py /absolute/project/build/change-001 /absolute/project/build/change-002
```

세션 디렉터리의 `agent-*.json`과 `repair-*/agent-*.json`을 `attemptId`로 중복 없이 모아 전체와 `provider/model/effort`별로 시도·수락·재시도 수, 토큰, 비용, 시간과 **COMPLETED 세션당 토큰·비용**을 출력한다. usage가 없는 과거 시도가 섞이면 세션당 토큰을 계산하지 않고 `attemptsWithoutUsage`로 표시한다. JEV 호출과 사람의 검토 시간은 포함하지 않는다. 비용은 provider가 금액을 보고한 경우(Claude)만 합산하며, 구독 플랜에서는 토큰 수를 사용량 지표로 본다.
