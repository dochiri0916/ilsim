# 폰에서 ask 쓰기

폰으로 **상태 보기, 요청 보내기, 계획 승인**만 한다. 실제 실행은 늘 내 PC에서 요금제 로그인으로 돈다.
폰에는 코드가 가지 않고, 상태 줄과 `ask` 출력 끝부분(최대 4000자)만 간다.

## 텔레그램 봇 (추천, 가입·서버 없음)

처음 한 번:

1. 텔레그램에서 `@BotFather`에게 `/newbot`을 보내고 이름을 정하면 봇 토큰을 준다.
2. PC의 `~/.secrets`에 넣는다.
   ```
   TELEGRAM_BOT_TOKEN=<받은 토큰>
   ```
3. 만든 봇에게 아무 메시지나 보낸 뒤, Gradle 프로젝트 폴더에서 `ask-remote`를 실행한다. 화면에 나온 `TELEGRAM_CHAT_ID=...` 줄을 `~/.secrets`에 추가한다.

쓰기: PC에서 Gradle 프로젝트 폴더로 가서 `ask-remote`를 켜 둔다 (`install.sh`가 설치). 그다음 봇에게 보낸다.
`ask-remote`가 실행하는 `ask`는 새 요청마다 스스로 업데이트하지만, 켜 둔 `ask-remote` 자신은 다시 켜야 새 코드가 반영된다.

- 하고 싶은 일 한 줄: PC에서 `ask "요청"`과 같다. 코드를 바꾸는 요청은 초안과 계획만 만들고 멈춘다.
- `승인`: 지금 작업이 승인 대기 중일 때만 `ask --yes`를 실행한다.
- `상태`: 지금 상태를 보여준다.

`TELEGRAM_CHAT_ID`의 대화만 읽는다. 다른 사람이 봇에게 보낸 말은 무시한다. 러너가 꺼져 있을 때 보낸 메시지는 실행하지 않는다.

## Vercel 페이지 (선택)

`TELEGRAM_BOT_TOKEN`이 없을 때만 쓴다.

1. Vercel에서 이 저장소로 새 프로젝트를 만들고 **Root Directory**를 `code-risk-analyzer/remote`로 둔다 (Hobby 무료).
2. 프로젝트의 Storage(Marketplace)에서 **Upstash Redis**를 연결한다. `KV_REST_API_URL`, `KV_REST_API_TOKEN`이 자동으로 들어간다.
3. Environment Variables에 두 값을 넣고 다시 배포한다. 둘 다 12자 이상, 서로 다르게.
   - `ASK_PHONE_PASSWORD`: 폰에서 입력할 비밀번호
   - `ASK_RUNNER_TOKEN`: PC 러너가 쓰는 토큰
4. PC의 `~/.secrets`에 추가한다.
   ```
   ASK_REMOTE_URL=https://<프로젝트>.vercel.app
   ASK_RUNNER_TOKEN=<3에서 넣은 값>
   ```

폰에서 주소를 열고 비밀번호를 넣는다. 승인은 폰에 보이던 그 작업이 PC의 현재 작업이고 승인 대기 중일 때만 실행한다.
틀린 비밀번호가 10분에 20번 넘게 오면 그 10분 동안 모두 잠긴다.

AI 게이트웨이나 API 키는 쓰지 않는다.
