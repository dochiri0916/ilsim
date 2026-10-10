# 다른 PC(회사 등)에 설치하고 자동으로 최신 유지

이 저장소는 private이다. 개인 GitHub 계정으로 로그인할 수 없는 PC에서는 **Deploy key**(이 저장소 하나만 읽는 SSH 키)로 받는다. 한 번 설치하면 `ai`가 작업을 받을 때마다 이 checkout을 최신으로 맞추고 바뀐 쪽만 다시 빌드하므로, 집에서 push만 하면 된다.

## 시작 전 확인

- **회사 정책**: `ai`는 프로젝트 코드와 요청을 Codex·Claude에, `TYPESAFE_API_KEY`가 있으면 위험 분석 항목의 메서드 소스를 JEV(TypeSafe)에도 보낸다. 회사 코드를 개인 구독 계정이나 외부 서비스로 보내도 되는지, 개인 저장소의 도구를 설치해도 되는지 먼저 확인한다. 허용되지 않으면 키를 두지 않거나 설치하지 않는다.
- **도구**: JDK 21(분석기·build-convention의 Gradle toolchain, 자동 다운로드 설정 없음; 분석기 JAR 실행은 Java 21 이상), python3(3.9 이상, macOS 기본 포함), git, codex 또는 claude CLI. [`install.sh`](../../../install.sh)가 확인한다.
- **네트워크**: github.com SSH(22번 포트)가 열려 있어야 한다. 막혀 있으면 아래 "SSH가 막힌 경우"를 쓴다.

## 1. 그 PC에서 키 만들기

```bash
ssh-keygen -t ed25519 -f ~/.ssh/ai-orchestration -N "" -C "work-pc"
cat ~/.ssh/ai-orchestration.pub
```

비밀번호 없는 키(`-N ""`)라야 `ai`가 묻지 않고 pull한다. 출력된 공개키 한 줄을 복사한다. 개인키(`~/.ssh/ai-orchestration`)는 그 PC 밖으로 옮기지 않는다.

## 2. GitHub에 공개키 등록 (계정이 있는 곳에서)

`dochiri0916/ai-orchestration` → Settings → Deploy keys → Add deploy key

- Title: `work-pc` 등 PC를 알아볼 이름
- Key: 1에서 복사한 공개키
- **Allow write access는 체크하지 않는다** (읽기 전용)

키 하나는 저장소 하나에만 등록할 수 있다. PC마다 따로 만든다.

## 3. 그 PC에서 받기와 설치

```bash
cat >> ~/.ssh/config <<'EOF'
Host ai-orchestration
  HostName github.com
  User git
  IdentityFile ~/.ssh/ai-orchestration
  IdentitiesOnly yes
EOF
ssh -T ai-orchestration          # "successfully authenticated" 가 나오면 성공
git clone ai-orchestration:dochiri0916/ai-orchestration.git ~/tools/ai-orchestration
cd ~/tools/ai-orchestration && ./install.sh
```

`install.sh`가 분석기 jar와 build-convention(`~/.m2`)을 빌드하고 `~/.local/bin/ai`·`ask-remote`를 등록한다. codex·claude CLI에는 요금제 계정으로 한 번 로그인한다(API 키를 넣으면 종량제 과금이 될 수 있다). JEV 키는 `~/.secrets`에 `TYPESAFE_API_KEY=...`로 둔다.

## 자동 업데이트

`ai 작업 내용`을 실행하면 먼저 [`scripts/self_update.py`](../../scripts/self_update.py)가 다음을 한다.

1. 최대 10분에 한 번 `git fetch`로 원격을 확인하고, 앞서 있으면 fast-forward pull한다. pull했으면 같은 명령을 새 코드로 다시 실행한다.
2. 분석기(`code-risk-analyzer/src`·빌드 파일)가 바뀌었으면 `bootJar`, build-convention이 바뀌었으면 `publishToMavenLocal`을 실행한다. 스크립트·문서만 바뀌면 빌드하지 않는다.

하지 않는 경우:

| 상황 | 동작 |
|---|---|
| 이 도구 폴더에 커밋하지 않은 수정이 있음 | pull·빌드 건너뜀 |
| 원격과 커밋이 갈라짐 | 건너뜀, 직접 정리 후 `git pull` |
| 네트워크·인증 실패 | 지금 버전으로 실행, 10분 뒤 다시 확인 |
| 빌드 실패 | 이전 jar·플러그인으로 실행, 같은 버전은 다시 빌드하지 않음(다음 push에서 재시도) |
| `ai --why`, 작업 내용 없이 `ai` | 업데이트하지 않음 |
| `ASK_NO_UPDATE=1` | 끔 |

지금 바로 확인하려면 `python3 code-risk-analyzer/scripts/self_update.py`를 실행한다(10분 제한 없이 확인). 기록은 `.git/ask-update.json`에 있어 작업 트리를 바꾸지 않는다.

`ask-remote`는 자기 코드가 바뀐 것을 보면 다음 명령 확인 사이에 새 코드로 다시 시작한다.

## SSH가 막힌 경우

Fine-grained personal access token을 쓴다. GitHub → Settings → Developer settings → Fine-grained tokens에서 Repository access를 `ai-orchestration` 하나로, Permissions를 **Contents: Read-only**로 만든다. 만료일이 있으므로 만료 전에 새로 만든다.

```bash
git clone https://github.com/dochiri0916/ai-orchestration.git ~/tools/ai-orchestration
# 사용자 이름: 아무 값, 비밀번호: 토큰
git -C ~/tools/ai-orchestration config credential.helper store   # 자동 pull을 위해 토큰을 ~/.git-credentials에 저장
```

`credential.helper store`는 토큰을 평문 파일에 둔다. OS 키체인 helper(macOS `osxkeychain`, Windows `manager`)가 있으면 그것을 쓴다.

## 키를 거둘 때

PC를 반납하거나 키가 유출되면 GitHub의 Deploy keys(또는 Fine-grained tokens)에서 해당 항목을 삭제한다. 그 즉시 그 PC의 pull이 실패하고, `ai`는 마지막으로 받은 버전으로 계속 실행된다.
