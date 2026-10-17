rootProject.name = "ilsim"

// 일심(Ilsim)은 하나의 서비스다: 서버, Runner, 분석기를 한 빌드로 묶는다. 웹(ilsim-web)은 npm 프로젝트라 제외한다.
include("ilsim-server", "ilsim-runner", "code-risk-analyzer")

// 선택 적용하는 Gradle Convention Plugin. 배포 대상 플러그인이라 별도 빌드로 두고 복합 빌드로만 연결한다.
includeBuild("build-convention")
