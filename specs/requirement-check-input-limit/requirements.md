# 요구사항 판정 입력 한도

## 배경

`RequirementCheck.judge`는 요구사항 목록 전체와 diff 앞 60,000자(`MAX_DIFF_CHARS`)를 JEV 요청 하나에 넣는다. JEV 입력 한도는 약 32k 토큰(실측 2026-10-25: 한글 30,000자·코드 60,000자 근처)이라 diff가 크거나 요구사항이 많으면 요청이 400으로 실패하고, 판정이 `UNKNOWN`이 되며 Jev 차단기가 60초 동안 다른 판정까지 막는다. `Guidelines`는 같은 문제를 `Guidelines.weight`(ASCII 0.5, 그 밖 1)로 세어 요청 하나의 state를 `MAX_STATE_WEIGHT`(22,000) 이하, diff를 `MAX_DIFF_WEIGHT`(12,000) 이하로 두는 방식으로 해결했다. 요구사항 판정도 같은 기준을 따른다.

## 요구사항

- REQ-1: 요구사항 판정에 넣는 diff를 `Guidelines.cut(diff, Guidelines.MAX_DIFF_WEIGHT)`와 같은 기준으로 자른다.
  - 인수 조건: `weight`가 12,000을 넘는 diff를 넘기면 JEV 요청의 `diff` 값은 `weight`가 12,000 이하인 원문 앞부분이다.
  - 인수 조건: `weight`가 12,000 이하인 diff는 원문 그대로 들어간다.
  - 인수 조건: 문자 수 기준 상수 `MAX_DIFF_CHARS`(60,000자)로 자르지 않는다.
- REQ-2: 요구사항 목록을 여러 JEV 요청으로 나눠, 요청 하나의 분량이 `Guidelines.MAX_STATE_WEIGHT` 이하가 되게 한다.
  - 인수 조건: 요청마다 (잘린 diff의 `weight`) + (그 요청에 든 요구사항의 `weight` 합)이 22,000 이하다. 요구사항 하나의 `weight`는 ID와 본문의 `weight` 합으로 센다.
  - 인수 조건: 한 요청에 드는 요구사항은 `Guidelines.BATCH`(10)개 이하다.
  - 인수 조건: 요구사항 순서를 유지해 앞에서부터 채우고, 다음 요구사항을 넣으면 한도를 넘을 때 새 요청을 시작한다.
  - 인수 조건: 모든 요구사항이 정확히 한 요청에만 들어간다.
  - 인수 조건: 분량이 한도 안이고 10개 이하이면 지금처럼 요청 하나로 묻는다.
- REQ-3: 나눈 요청의 답을 합쳐 지금과 같은 `Result`를 돌려준다.
  - 인수 조건: `probabilities`는 요구사항 ID마다 그 요구사항이 든 요청의 충족 확률이다.
  - 인수 조건: 요청마다 질문 ID(`m*`·`d*`)는 그 요청 안에서만 쓰이고, 다른 요청의 요구사항과 섞이지 않는다.
  - 인수 조건: 요청 중 하나라도 JEV가 답하지 못하면(`null`) `judge`는 `null`을 돌려준다(지금처럼 `UNKNOWN`).
  - 인수 조건: 같은 입력이면 같은 요청들이 만들어져 `Jev` 캐시가 그대로 맞는다.
- REQ-4: diff가 잘리면 누락 판정을 하지 않는다(지금 동작 유지).
  - 인수 조건: 잘렸으면 `truncated=true`, `missing`은 비어 있고, 충족 확률이 `MET`(0.8) 미만인 요구사항은 모두 `uncertain`이다(0.2 이하도 `uncertain`).
  - 인수 조건: 잘리지 않았으면 0.2 이하는 `missing`, 그 밖에 0.8 미만은 `uncertain`이다(지금과 같다).
  - 인수 조건: `IlsimMcp`의 `requirements` 검증 값과 `REQUIREMENT_CHECK` 판단 기록(잘렸을 때 "diff 일부만 판정")은 바뀌지 않는다.

## 예외 상황

- 요구사항 하나만으로 (diff `weight` + 요구사항 `weight`)가 22,000을 넘는 경우: 그 요구사항 혼자 요청 하나를 이룬다. diff가 12,000 이하로 잘리므로 요구사항 본문이 약 10,000(한글 약 1만 자)을 넘을 때만 생기며, 요구사항 본문은 자르지 않는다(미결정 사항 1).
- JEV가 꺼져 있거나 요구사항이 없으면 지금처럼 `null`을 돌려준다(요청을 보내지 않는다).
- 빈 diff: 자르지 않고 그대로 넣으며 `truncated=false`다.
- 요청 일부가 실패해 차단기가 걸린 경우: 남은 요청도 `null`이 되어 전체가 `UNKNOWN`이 된다(부분 결과를 쓰지 않는다).

## 기존 기능 영향

- `RequirementCheck.judge`의 시그니처와 `Result` 구조는 그대로다. 호출부 `IlsimMcp.requirementCheck`·`auditNeeded`는 바꾸지 않는다.
- diff 한도가 60,000자에서 `weight` 12,000(ASCII 약 24,000자)으로 줄어, 지금은 잘리지 않던 24,000~60,000자 diff가 잘린다. 이 경우 누락(`MISSING`) 대신 불확실(`UNCERTAIN`)이 되어 구현 단계에서 독립 검토가 붙는 일이 늘어난다.
- 요구사항이 10개를 넘는 작업은 JEV 요청이 늘어난다(비용 소폭 증가). JEV 점수는 함께 든 항목에 따라 달라질 수 있어, 요구사항이 10개를 넘는 작업은 같은 diff라도 지금과 확률이 다를 수 있다.
- 캐시 키가 state에 따라 정해지므로 기존 캐시는 10개 이하·diff 24,000자 이하 작업에서만 다시 쓰인다.
- `JevRequirementEval`은 `RequirementCheck.MET`·`MISSING`·`questions`만 쓰므로 영향이 없다. `MAX_DIFF_CHARS`를 쓰는 다른 코드는 없다.
- `Guidelines`의 상수·`weight`·`cut`은 공유만 하고 동작을 바꾸지 않는다.

## 미결정 사항

1. 요구사항 하나가 혼자서도 한도를 넘을 때 본문을 잘라야 하는가? (안: 자르지 않음 — 실제로 생기기 어렵고 요구사항 원문을 보존 / 남은 분량에 맞춰 본문을 `cut`으로 자름 — 400을 확실히 막지만 판정 근거가 줄어듦. 추천: 자르지 않음)
2. 나눈 요청을 `Guidelines.PARALLEL`(4)개로 병렬 처리할 것인가, 순서대로 보낼 것인가? (추천: `Guidelines.check`와 같이 병렬)
3. `docs/mcp-design.md`·`contracts/runner-protocol.md`의 JEV 한도 설명에 요구사항 판정도 같은 한도를 따른다고 적을 것인가? (추천: 구현 단계에서 허용되면 한 줄 추가)
