# 훅 따옴표 안 리디렉션 오탐 테스트 케이스

요구사항 단계의 미결정 사항은 사용자가 정했다(2026-10-25): here-document 본문은 이번 범위에서 뺀다.

`McpTest`의 Bash 훅 테스트처럼 `hook("pre-tool-use", …)`에 `tool_name=Bash`와 명령을 넘겨 출력이 빈 문자열(허용)인지 `deny`(거부)를 담는지 본다. 따로 적지 않으면 활성 작업이 없는 등록 저장소에서 실행한다.

| ID | 요구사항 | 분류 | 시나리오 | 기대 결과 |
|---|---|---|---|---|
| TC-1 | REQ-1 | 정상 | `git merge --no-ff ilsim/slug-1 -m "merge: x" -m "Co-Authored-By: Claude <noreply@anthropic.com>"` | 허용 |
| TC-2 | REQ-1 | 정상 | `git log --format='%h > %s' -3` | 허용 |
| TC-3 | REQ-1 | 정상 | `echo "a > b"`, `printf 'x >> y\n'` | 둘 다 허용 |
| TC-4 | REQ-1 | 정상 | `grep "rm -rf" README.md`, `grep 'sed -i' README.md`, `git log --grep "mv a b"` | 모두 허용 |
| TC-5 | REQ-1 | 경계값 | `echo "a \" > b"`(큰따옴표 안 이스케이프된 따옴표 뒤 `>`) | 허용 |
| TC-6 | REQ-1 | 경계값 | `echo "" > README.md`(빈 따옴표 뒤 따옴표 밖 리디렉션) | 거부 |
| TC-7 | REQ-1 | 경계값 | `echo "a"'b > c'`(이어 붙은 따옴표, `>`는 작은따옴표 안) | 허용 |
| TC-8 | REQ-1 | 경계값 | `echo 'a\' > README.md`(작은따옴표 안 `\`는 이스케이프가 아님, `>`는 밖) | 거부 |
| TC-9 | REQ-2 | 예외 | `echo "a" > out.txt`, `echo 'x' >> src/a.kt`, `rm "a.txt"` | 모두 거부 |
| TC-10 | REQ-2 | 기존 기능 회귀 | `git log > /tmp/ilsim-log.txt`, `git log > "/tmp/ilsim-log.txt"`, `./gradlew test 2>&1 > /dev/null` | 모두 허용 |
| TC-11 | REQ-2 | 기존 기능 회귀 | `git log > "<저장소 경로>/log.txt"`(저장소 안 절대 경로, 따옴표로 감쌈) | 거부 |
| TC-12 | REQ-2 | 예외 | `echo "a > b`(닫히지 않은 따옴표) | 거부(따옴표를 빼지 않고 전체 검사) |
| TC-13 | REQ-3 | 예외 | `bash -c "echo x > a"`, `sh -c 'rm a'`, `zsh -c "touch a"` | 모두 거부 |
| TC-14 | REQ-3 | 예외 | `eval "echo x > a"` | 거부 |
| TC-15 | REQ-3 | 예외 | `echo "$(echo x > a)"`, ``echo "`rm a`"`` | 둘 다 거부 |
| TC-16 | REQ-4 | 기존 기능 회귀 | `git commit -m "x"` (활성 작업 없음, 활성 작업 있음) | 둘 다 거부 |
| TC-17 | REQ-4 | 상태 전이 | 활성 작업(IMPLEMENTATION, RUNNING) 중 `git merge ilsim/other -m "a <b@c.d>"` | "진행 중인 일심 작업" 거부 |
| TC-18 | REQ-1, REQ-4 | 상태 전이 | 활성 작업이 승인 대기(WAITING_APPROVAL)일 때 `echo "a > b"`와 `echo a > README.md` | 앞은 허용, 뒤는 승인 대기 거부 |
| TC-19 | REQ-4 | 기존 기능 회귀 | 기존 `McpTest` Bash·Edit 훅 테스트 | 모두 그대로 통과 |
| TC-20 | REQ-1 | 기존 기능 회귀 | 설계 문서 `docs/mcp-design.md` 5. 강제(훅) 표의 Bash 차단 조건 | 따옴표 안 글자는 검사하지 않고 셸 `-c`·`eval`·명령 치환은 검사한다는 문구가 있다 |

## 제외한 분류

- 동시성: 훅은 명령 하나를 받아 판단하고 끝나는 단일 프로세스이며 공유 상태를 쓰지 않는다.
- 멱등성: 판단은 입력 문자열만으로 정해지고 상태를 바꾸지 않는다.
- 트랜잭션/롤백: 저장이 없다.
- 인증·인가: 훅에 인증 경로가 없다.
- here-document: 사용자 결정으로 범위 밖이다.
