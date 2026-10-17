# 저장소 개발과 이력

## 빌드와 검증

각 프로젝트의 Gradle Wrapper를 해당 디렉터리에서 실행한다.
다음 두 명령 블록은 각각 저장소 루트에서 시작한다.

```bash
cd code-risk-analyzer
./gradlew clean build --no-daemon
```

```bash
cd build-convention
./gradlew clean check --no-daemon
```

상세 실행 방법과 지원 범위는 각 프로젝트 문서를 따른다.

## 저장소 이전

기존 프로젝트를 `git subtree`로 가져왔으며 `--squash`를 사용하지 않았다. 기존 커밋 SHA와 작성자·작성일을 유지하고 두 프로젝트의 이력을 이 저장소의 `main`에 연결했다. 분석기의 기존 미추적 소스는 별도 복사본에서 신규 커밋으로 기록한 뒤 가져왔으며, 이후 원본 저장소에 푸시된 정식 커밋도 병합했다.

기존 GitHub 브랜치는 `history/<project>/<branch>`에 보존한다. 기존 태그가 있으면 `<project>/<tag>` 이름으로 보존한다.

분석기 원본 저장소는 유지한다. Build Convention 원본 GitHub 저장소는 사용자 요청으로 삭제했으며, 코드·커밋·브랜치는 이 저장소에 보존한다. 원본 PR 기록은 이전되지 않았다.

- Build Convention 원본 Git 이력: `history/build-convention/main`
- [Code Risk Analyzer 원본](https://github.com/dochiri0916/code-risk-analyzer)

이 저장소에서 수정한 코드는 `ilsim`의 `main`으로 푸시한다. 분석기 원본 저장소의 추가 변경은 `git subtree`로 병합한다.

분석기 원본의 비공개 설정에 맞춰 이 통합 저장소도 비공개로 생성했다. 프로필에 비공개 기여를 표시하려면 GitHub의 private contributions 표시 설정을 사용한다.
