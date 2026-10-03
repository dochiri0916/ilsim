# 폰에서 ask 쓰기 (Vercel)

폰으로 **상태 보기, 요청 보내기, 계획 승인**만 한다. 실제 실행은 늘 내 PC에서 요금제 로그인으로 돈다.
Vercel에는 코드가 올라가지 않고, PC가 보내는 상태 줄과 `ask` 출력 끝부분(최대 4000자)만 저장된다.

## 처음 한 번

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

## 쓰기

PC에서 Gradle 프로젝트 폴더로 가서 `ask-remote`를 켜 둔다 (`install.sh`가 설치). 15초마다 폰 명령을 확인한다.
폰에서 주소를 열고 비밀번호를 넣으면 된다.

- 요청 보내기: PC에서 `ask "요청"`과 같다. 코드를 바꾸는 요청은 초안과 계획만 만들고 멈춘다.
- 승인: 폰에 보이던 그 작업이 PC의 현재 작업이고 승인 대기 중일 때만 `ask --yes`를 실행한다.
- 틀린 비밀번호가 10분에 20번 넘게 오면 그 10분 동안 모두 잠긴다.

AI 게이트웨이나 API 키는 쓰지 않는다. Vercel 함수는 Redis에 상태와 명령만 읽고 쓴다.
