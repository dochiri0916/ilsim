# 빌드·검증 문서에 corpus check 안내 추가

## 배경

- `docs/repository.md`의 `## 빌드와 검증` 절은 Gradle 명령(`./gradlew clean build`, `check`, `:ilsim-server:test`)과 웹 빌드(`npm run build`)만 안내한다.
- `ilsim-runner corpus check [id...]`는 시험용 프로젝트 모음의 채굴 결과를 저장된 기준과 비교하는 회귀 확인 명령이다(`ilsim-runner/src/main/kotlin/com/dochiri/ilsim/runner/Main.kt`의 `USAGE`와 `corpus()`). AI를 쓰지 않고, 변화·라벨 문제·커밋 불일치가 있으면 종료 코드 1을 낸다.
- 옵션: `--base <폴더>`(기본 `ILSIM_CORPUS_BASE` 또는 `~/programming`), `--data <폴더>`(기본 `ilsim-runner/corpus`). `--data` 기본값이 상대경로라 저장소 루트에서 실행해야 한다.
- 실행 파일은 `./gradlew :ilsim-runner:installDist` 후 `ilsim-runner/build/install/ilsim-runner/bin/ilsim-runner`에 생긴다(`docs/handoff.md` 156행).

## 영향 범위

- 수정 대상: `docs/repository.md` 한 파일의 `## 빌드와 검증` 절.
- 코드·빌드 스크립트·다른 문서는 변경하지 않는다.

## 요구사항

### REQ-1. corpus check 사용법 한 줄 추가
`docs/repository.md`의 `## 빌드와 검증` 절에 `ilsim-runner corpus check` 사용법을 정확히 한 줄 추가한다.

인수 조건:
- 변경 diff가 `docs/repository.md`에서 추가 한 줄(주석 정렬 등 불가피한 인접 줄 수정 제외)이다.
- 추가한 줄이 `## 빌드와 검증` 절 안(`## 저장소 이전` 앞)에 있다.
- 줄에 `ilsim-runner corpus check` 명령이 `Main.kt`의 `USAGE`와 같은 형태(`corpus check [id...]`)로 들어 있다.
- 줄 설명에 "시험용 모음을 기준과 비교한다"와 "차이가 있으면 종료 코드 1"이 드러난다.

### REQ-2. 기존 문서 스타일 준수
인수 조건:
- 기존 `bash` 코드 블록(9~11행) 안에 같은 형식(명령 + 공백 정렬 + `#` 한국어 설명)으로 넣거나, 블록 밖이라면 기존 문장 톤(한국어 평서문, `~한다`)을 따른다.
- 명령·옵션·환경 변수 이름은 원문 그대로 쓴다.
- 기존 줄의 내용은 바꾸지 않는다.

### REQ-3. 사실과 일치
인수 조건:
- 줄의 내용이 `Main.kt`의 `corpus` `check` 동작(기준 비교, 불일치 시 종료 코드 1)과 모순되지 않는다.
- 존재하지 않는 옵션·경로를 쓰지 않는다.

## 예외 상황

- 명령 형태를 코드 블록에 넣으면 `ilsim-runner`가 PATH에 없는 환경에서는 그대로 실행되지 않는다. 설치 경로(`installDist` 산출물)와의 관계를 어떻게 보일지 정해야 한다(미결정 사항 1).
- `--data` 기본값이 상대경로이므로 저장소 루트가 아닌 곳에서 실행하면 기준 폴더를 못 찾는다.
- 기준 프로젝트가 `~/programming` 아래에 없으면 `--base` 또는 `ILSIM_CORPUS_BASE`가 필요하다. 한 줄에 다 담기 어려우면 옵션 설명은 생략한다.
- 줄이 길어져 기존 코드 블록 주석 정렬이 깨질 수 있다.

## 기존 기능 영향

- 런타임·빌드·테스트 동작에 영향 없음(문서만 변경).
- `docs/handoff.md`나 `README.md`와 안내가 중복될 수 있으나 이 요구사항에서는 수정하지 않는다.
- 문서에서 모듈별 테스트(`./gradlew :ilsim-runner:test`)가 `CorpusTest`를 돌리므로, `corpus check`는 이를 대체하지 않고 실제 시험용 모음 데이터를 확인하는 별도 단계다.

## 미결정 사항

1. 명령을 어떤 형태로 적을까? (a) `ilsim-runner corpus check`만, (b) `ilsim-runner/build/install/ilsim-runner/bin/ilsim-runner corpus check`처럼 설치 경로 포함, (c) `./gradlew :ilsim-runner:run --args="corpus check"`. 마지막은 현재 빌드에서 `run` 태스크 지원 여부 미확인이다.
2. 기존 `bash` 블록에 넣을까, 블록 밖 문장으로 쓸까? (기본 가정: 블록 안 한 줄)
3. `[id...]`와 `--base`/`--data` 옵션을 줄에 표기할까, 생략할까?
4. `installDist` 선행 필요를 같은 줄에 언급할까?
