# 요구사항 판정 입력 한도 테스트 케이스

요구사항 단계의 미결정 사항은 사용자가 추천안으로 정했다(2026-10-25): 한도를 넘는 요구사항 하나는 본문을 자르지 않고 혼자 요청 하나로 묻고, 나눈 요청은 `Guidelines.PARALLEL`개로 병렬 처리하며, 설계 문서에 요구사항 판정도 같은 한도를 따른다고 적는다.

JEV 호출은 `JevTest`의 `FakeTransport`처럼 요청 본문을 기록하는 가짜 전송으로 확인한다. 분량은 `Guidelines.weight`로 센다.

| ID | 요구사항 | 분류 | 시나리오 | 기대 결과 |
|---|---|---|---|---|
| TC-1 | REQ-1 | 정상 | `weight`가 12,000 이하인 diff와 요구사항 2개로 `judge` | 요청 1건, 요청 state의 `diff`가 원문과 같고 `truncated=false` |
| TC-2 | REQ-1, REQ-4 | 경계값 | `weight`가 정확히 12,000인 diff(한글 12,000자) | 자르지 않음, `truncated=false` |
| TC-3 | REQ-1, REQ-4 | 경계값 | `weight`가 12,001인 diff(한글 12,001자) | state의 `diff`가 `Guidelines.cut(diff, 12_000)`과 같고 `truncated=true` |
| TC-4 | REQ-1 | 경계값 | ASCII 30,000자 diff(`weight` 15,000, 기존 60,000자 한도 안) | 잘림(`truncated=true`), state의 `diff`가 ASCII 24,000자 |
| TC-5 | REQ-2 | 정상 | 짧은 요구사항 10개, 작은 diff | 요청 1건에 요구사항 10개가 모두 든다 |
| TC-6 | REQ-2 | 경계값 | 짧은 요구사항 11개, 작은 diff | 요청 2건(10개·1개), 순서대로 앞에서부터 채워짐 |
| TC-7 | REQ-2 | 경계값 | 잘린 diff(`weight` 12,000)와 본문 한글 4,000자 요구사항 4개 | 요청이 2건 이상이고, 요청마다 diff `weight` + 든 요구사항 `weight` 합이 22,000 이하 |
| TC-8 | REQ-2 | 정상 | TC-7·TC-6의 요청들에 든 요구사항 ID를 모음 | 모든 요구사항 ID가 정확히 한 번씩 나타나고, 원래 순서와 같다 |
| TC-9 | REQ-2 | 예외 | 잘린 diff(`weight` 12,000)와 본문 한글 11,000자 요구사항 1개 + 짧은 요구사항 1개 | 큰 요구사항은 본문이 잘리지 않은 채 혼자 요청 1건, 짧은 요구사항은 다른 요청에 든다 |
| TC-10 | REQ-3 | 정상 | 요청 2건으로 나뉘는 요구사항 11개, 가짜 전송이 요구사항마다 다른 확률을 돌려줌 | `probabilities`가 11개 ID 모두를 담고, 각 ID의 값이 그 요구사항이 든 요청의 `m*` 답이다 |
| TC-11 | REQ-3 | 정상 | 요청 2건으로 나뉠 때 요청별 질문 ID | 요청마다 `m0`부터 다시 시작하는 `m*`·`d*`가 그 요청의 요구사항 수만큼만 있고, 질문 본문은 그 요청에 든 요구사항 ID만 가리킨다 |
| TC-12 | REQ-3 | 예외 | 요청 2건 중 하나에서 전송이 실패 | `judge`가 `null`(부분 결과를 쓰지 않음) |
| TC-13 | REQ-3 | 멱등성 | 같은 요구사항·diff로 `judge`를 두 번 호출(캐시 디렉터리 사용) | 두 번째 호출은 전송 요청이 늘지 않고 결과가 같다 |
| TC-14 | REQ-3 | 동시성 | 요청 5건 이상으로 나뉘는 요구사항, 가짜 전송이 응답을 늦게 돌려줌 | 결과가 요구사항 순서·확률 모두 순차 처리와 같고, 동시에 진행되는 요청이 `Guidelines.PARALLEL`(4)개를 넘지 않는다 |
| TC-15 | REQ-4 | 정상 | 잘리지 않은 diff, 확률 0.1·0.5·0.9 요구사항 | `missing`=[0.1], `uncertain`=[0.5], `truncated=false` |
| TC-16 | REQ-4 | 경계값 | 잘리지 않은 diff, 확률 0.2·0.8 요구사항 | 0.2는 `missing`, 0.8은 `missing`·`uncertain` 모두 아님 |
| TC-17 | REQ-4 | 예외 | 잘린 diff, 확률 0.1·0.5·0.9 요구사항 | `missing` 비어 있음, `uncertain`=[0.1, 0.5], `truncated=true` |
| TC-18 | REQ-4 | 기존 기능 회귀 | 구현 단계 제출에서 diff가 잘리고 확률 0.1 요구사항이 있음 | `validation["requirements"]=UNCERTAIN`, `REQUIREMENT_CHECK` 기록에 "diff 일부만 판정" |
| TC-19 | REQ-3 | 기존 기능 회귀 | JEV 꺼짐 또는 요구사항 0개 | `judge`가 `null`, 전송 요청 0건 |
| TC-20 | REQ-1 | 경계값 | 빈 diff | 요청 1건, state의 `diff`가 빈 문자열, `truncated=false` |
| TC-21 | REQ-3 | 기존 기능 회귀 | `JevRequirementEval` 기존 테스트 | `RequirementCheck.MET`·`MISSING`·`questions`를 쓰는 동작이 그대로 통과 |
| TC-22 | REQ-2 | 기존 기능 회귀 | 설계 문서(`docs/mcp-design.md`) JEV 입력 한도 문장 | 요구사항 판정도 같은 한도(state 22,000·diff 12,000, 잘리면 누락 없이 불확실)를 따른다고 적혀 있다 |

## 제외한 분류

- 상태 전이: `RequirementCheck`는 상태를 두지 않는 판정 함수다. 단계 상태는 바꾸지 않는다.
- 트랜잭션/롤백: DB·파일 쓰기가 없다(캐시는 `Jev`의 기존 동작이며 이번 변경 대상이 아니다).
- 인증·인가: JEV 키와 토큰 처리는 바꾸지 않는다.
