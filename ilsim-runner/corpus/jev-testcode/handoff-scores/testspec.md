# 인수인계 문서 점수 기록 — 테스트 케이스

| ID | 요구사항 | 분류 | 시나리오 | 기대 결과 |
|----|----------|------|----------|-----------|
| TC-1 | REQ-1 | 정상 | 바뀐 `docs/handoff.md`에서 guidelines CLI 절 찾기 | 문서별 최종 관련도 줄이 있다 |
| TC-2 | REQ-2 | 기존 기능 회귀 | `git diff main -- docs/handoff.md` | 지우거나 고친 줄이 없고 추가한 줄만 있다 |
| TC-3 | REQ-3 | 범위 | `git diff --name-only main` | 바뀐 파일이 `docs/handoff.md`뿐이다 |
