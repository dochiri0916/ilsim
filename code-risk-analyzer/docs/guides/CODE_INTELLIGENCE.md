# Java 코드 인텔리전스와 의존성 기반 내장 방식

이 문서는 코드 인텔리전스가 에이전트의 파일 탐색을 줄이는 원리와, 현재 프로젝트에 별도 `jdtls` 설치 없이 적용하는 방향을 정리한다. 아래의 조회 기능과 연동 방식은 구현 제안이며 현재 제공되는 명령이 아니다.

## 결론

Java 분석 라이브러리를 Gradle 의존성으로 받아 분석기 JAR에 포함하면, 사용자가 `jdtls`나 IDE 플러그인을 추가로 설치하지 않아도 코드 탐색 기능을 제공할 수 있다. 현재 프로젝트는 이미 JavaParser Symbol Solver를 사용하므로 이를 확장하는 방향을 추천한다.

다만 라이브러리 의존성만 추가한다고 에이전트가 자동으로 활용하지는 않는다. 정의·참조·호출 관계를 조회하는 기능을 구현하고, 에이전트가 CLI 또는 MCP 도구로 호출하도록 연결해야 한다.

여기서 추가 설치가 없다는 뜻은 별도의 분석 프로그램 설치가 필요 없다는 뜻이다. 기존 분석기 JAR과 Java 21 이상의 실행 환경은 계속 필요하다.

## 불필요한 파일 읽기를 줄이는 원리

텍스트 검색은 이름이 같은 클래스나 메서드를 함께 찾는다. 코드 인텔리전스는 소스 구조와 타입을 해석해 조회 대상의 선언과 사용 위치를 구분한다.

예를 들어 `OrderService.save()`의 호출처를 확인할 때 다른 클래스에 선언된 `save()`의 검색 결과를 일일이 읽는 탐색을 줄일 수 있다. 에이전트에는 관련 파일 경로·줄 위치·메서드 시그니처를 먼저 반환하고, 필요한 코드만 추가로 읽게 한다.

이 방식은 파일 탐색과 모델 문맥에 들어가는 코드의 양을 줄일 수 있지만, 절감률은 작업과 에이전트의 도구 사용 여부에 따라 달라진다. 테스트·설정·업무 로직을 읽어야 하는 작업은 여전히 남는다. 분석기가 인덱싱을 위해 여러 파일을 읽는 것과 모델이 그 파일 전체를 문맥에 넣는 것은 별개다.

## 설치·배포·의존성의 차이

| 방식 | 준비할 것 | 실행 방식 | 적용 시 고려할 점 |
|---|---|---|---|
| `jdtls` + LSP 플러그인 | 언어 서버 실행 파일과 에이전트용 플러그인 | 에이전트가 로컬 언어 서버 프로세스를 실행 | 별도 공개 서버 배포는 필요 없지만 실행 파일은 필요함 |
| 기존 IntelliJ IDEA의 MCP 서버 | 지원되는 IDE와 MCP 연결 설정 | 실행 중인 IDE가 분석 도구를 제공 | IDE가 이미 있다면 `jdtls` 추가 설치를 피할 수 있음 |
| 분석 라이브러리를 JAR에 내장 | Gradle 의존성과 조회 기능 구현 | 현재 분석기 프로세스에서 분석 | 별도 분석 도구 설치 없이 배포 가능하며 에이전트 연동 구현이 필요함 |

`jdtls`는 언어 서버이며, JavaParser Symbol Solver는 프로그램 내부에서 호출하는 분석 라이브러리다. 내장 라이브러리로 필요한 탐색 기능을 만들 수 있지만, 이것이 `jdtls`의 모든 LSP 기능을 그대로 제공한다는 뜻은 아니다.

IntelliJ IDEA는 2025.2부터 MCP 서버를 내장한다. 사용하는 IDE 버전의 제공 도구를 확인하고, `Settings → Tools → MCP Server`에서 활성화한 뒤 에이전트를 연결한다. 사용 중에는 IDE에서 대상 프로젝트를 열어두어야 한다. [JetBrains 공식 안내](https://www.jetbrains.com/help/idea/mcp-server.html)

## 현재 프로젝트에서 재사용할 수 있는 기반

[build.gradle](../../build.gradle)에 다음 의존성이 이미 포함되어 있다.

```groovy
implementation 'com.github.javaparser:javaparser-symbol-solver-core:3.28.2'
```

이 의존성은 JavaParser와 JavaSymbolSolver를 함께 제공한다. JavaParser는 소스의 AST를 만들고, JavaSymbolSolver는 사용된 심볼과 선언의 관계를 해석한다. [JavaParser 공식 설명](https://github.com/javaparser/javaparser#setup)

| 기존 구성 | 재사용할 수 있는 기능 |
|---|---|
| [JavaSourceParser](../../src/main/java/com/example/coderiskanalyzer/production/parser/JavaSourceParser.java) | Java 소스 파싱과 심볼 해석기 연결 |
| [SymbolSolverFactory](../../src/main/java/com/example/coderiskanalyzer/production/parser/SymbolSolverFactory.java) | 소스 루트·JDK·외부 JAR 및 클래스 디렉터리의 타입 정보 연결 |
| [CallGraphBuilder](../../src/main/java/com/example/coderiskanalyzer/production/analysis/callgraph/CallGraphBuilder.java) | 메서드 호출 관계 구성 |
| [CallGraph](../../src/main/java/com/example/coderiskanalyzer/production/analysis/callgraph/CallGraph.java) | 호출자·호출 대상 관계와 깊이가 제한된 탐색 |

현재 구성은 위험 분석의 내부 기능이다. 에이전트가 직접 호출하는 정의·참조 조회 명령은 아직 제공하지 않는다. 호출 그래프도 심볼 해석 실패 시 일부 이름 기반 추정을 사용하므로, 이를 그대로 정확한 참조 검색 결과로 취급해서는 안 된다.

분석 대상 프로젝트의 외부 의존성 정보도 필요하다. 현재는 `.code-risk.yml`의 `analysis.classpath`로 지정하며 대상 프로젝트의 Gradle/Maven 의존성을 자동으로 수집하거나 다운로드하지 않는다. 분석기 자체의 의존성과 분석 대상의 클래스패스는 각각 준비해야 한다. 자세한 범위는 [CLASSPATH](CLASSPATH.md)를 따른다.

## 추천하는 적용 방향

기존 분석기에 읽기 전용 조회 CLI를 먼저 추가하는 방향을 추천한다. 현재 JAR 배포 방식을 활용하면서 별도 언어 서버나 IDE 없이 사용할 수 있다. 분석 대상 프로젝트에 `build-convention`을 적용할 필요도 없다.

### 추가할 조회 기능

| 기능 | 에이전트에 반환할 정보 |
|---|---|
| 심볼 검색 | 클래스·메서드 후보, 파일 경로, 시그니처 |
| 정의 조회 | 선택한 심볼의 선언 위치와 타입 정보 |
| 호출자·호출 대상 조회 | 관련 메서드와 호출 위치 |
| 참조 조회 | 선택한 심볼의 사용 위치와 참조 종류 |

메서드 호출 관계부터 제공하고, 필드·타입·생성자·메서드 참조 등의 사용 위치는 지원 범위를 명시하며 확장한다. 현재 호출 그래프에는 모든 종류의 참조나 참조별 소스 위치가 포함되어 있지 않으므로 추가 구현이 필요하다.

결과는 관련 위치와 짧은 코드 조각을 중심으로 제한한다. 해석에 성공한 결과와 추정한 결과를 구분하고, 해석 실패를 참조 없음으로 표시하지 않는다. 조회 대상을 좁힐 수 있도록 파일·심볼·탐색 깊이·결과 개수 제한을 제공한다.

### 에이전트 연결

에이전트가 정의나 사용 위치를 찾을 때 조회 CLI를 호출하도록 지침과 실행 권한을 연결한다. 현재 [agent_pick.py](../../scripts/agent_pick.py)의 Claude 명령 허용 목록에는 일반적인 `java -jar` 조회 명령이 없으므로, 새 조회 명령을 사용할 수 있게 구체적으로 연결해야 한다.

MCP는 후속 선택지다. 같은 조회 기능을 MCP 도구로 노출할 수 있지만 현재 `ai`는 MCP를 기본으로 끄며, `--mcp`는 기존 연결을 유지하는 옵션이다. MCP 서버 구현·등록·도구 권한 연결 없이 `--mcp`만으로 이 기능이 생기지는 않는다.

반복 조회의 지연이 커지면 인덱스와 캐시를 추가하고, 소스·클래스패스 변경 시 갱신한다. CLI를 매번 실행해 전체 소스를 다시 분석하면 모델 입력은 줄어도 실행 시간은 늘 수 있다.

## 기대 효과와 한계

- 기대 효과: 이름이 같은 심볼의 불필요한 검색 결과를 줄이고, 관련 코드 위치를 먼저 제공해 파일 읽기를 좁힐 수 있다.
- 배포 방식: 필요한 분석 라이브러리를 기존 JAR에 포함해 사용자의 별도 `jdtls` 설치를 피할 수 있다.
- 정확도: 외부 의존성, 상속·제네릭 해석, 생성 코드 등에 따라 조회가 불완전할 수 있다. 미해석 범위를 결과에 함께 표시해야 한다.
- 실행 의미: 정적 호출 관계가 Spring 프록시·리플렉션·실제 런타임 구현 선택을 모두 확정하지는 않는다.
- 기능 범위: IDE 수준의 전체 진단·증분 분석·리팩터링은 별도 구현과 검증이 필요하다.
- 검증 계약: 코드 탐색 기능은 기존 위험 검사와 빌드·테스트 검증을 대체하지 않는다.

## 참고 자료

- [Claude Code 코드 인텔리전스 공식 안내](https://code.claude.com/docs/en/plugins/code-intelligence): LSP 기반 정의·참조 탐색과 언어 서버 설치 방식.
- [JavaParser 공식 저장소](https://github.com/javaparser/javaparser#setup): Gradle 의존성과 JavaSymbolSolver의 선언 해석 기능.
- [IntelliJ IDEA MCP 서버 공식 안내](https://www.jetbrains.com/help/idea/mcp-server.html): 내장 MCP 서버와 외부 에이전트 연결 방식.
