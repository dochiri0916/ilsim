# 타입 기반 부수 효과 판정과 외부 JAR

분석 대상 프로젝트 루트의 `.code-risk.yml`에 외부 의존 JAR을 지정할 수 있다.

```yaml
analysis:
  classpath:
    - libs/spring-data-commons.jar
    - libs/spring-web.jar
    - libs/dependency-api.jar
```

경로는 프로젝트 루트 기준 상대 경로이며 세 가지 형태를 쓸 수 있다.

| 형태 | 예 | 처리 |
|---|---|---|
| JAR 파일 | `libs/spring-web.jar` | 읽을 수 있는 올바른 JAR이어야 한다 |
| JAR glob 패턴 | `libs/*.jar`, `libs/**/*.jar` | `.jar`로 끝나야 하며, 프로젝트 안의 일치하는 JAR을 경로 순으로 펼친다. 하나도 없으면 설정 오류 |
| 클래스 디렉터리 | `build/classes/java/main` | `.class`가 하나 이상 있어야 한다. 바이트코드를 임시 JAR로 묶어 읽기만 하고 클래스를 로드·실행하지 않는다 |

절대 경로·루트 밖 경로·심볼릭 링크 경유(패턴의 고정 부분 포함)·없는 파일·잘못된 JAR·빈 클래스 디렉터리는 설정 오류로 거부한다. 패턴 탐색은 심볼릭 링크를 따라가지 않는다. 환경 변수 확장과 자동 의존성 수집은 지원하지 않으므로 필요한 전이 의존 JAR도 직접(또는 패턴으로) 지정해야 한다. 같은 항목이 여러 번 나오면 한 번만 등록한다.

`scan`의 전체·변경 파일 분석, 추가 생산 코드 문맥 파싱, baseline 생성에 동일하게 적용한다. 설정을 생략하면 기존 소스 루트와 JDK Reflection 기반 해석을 사용한다. 대상 Gradle/Maven 스크립트나 JAR의 코드를 실행하지 않으며, 의존성을 자동 다운로드하지 않는다.

## 판정 방식

`SideEffectDetector`는 호출의 선언 타입과 메서드 시그니처를 먼저 해석한다. 알려진 API의 메서드와 일치하는 override도 상속 타입·제네릭 인자를 확인해 연결한다. 같은 이름의 별도 overload나 임의의 `repository`·`client` 변수는 해석된 API가 다르면 부수 효과로 분류하지 않는다.

현재 인식하는 API와 후보:

| 영역 | API 및 호출 |
|---|---|
| DB 변경 | Spring Data Crud/ListCrud/JpaRepository의 save·delete 계열, deleteBy/removeBy 파생 메서드, JPA EntityManager·Hibernate Session·MyBatis SqlSession의 저장·삭제 호출 |
| JDBC | JdbcOperations/JdbcTemplate 및 NamedParameter 변형의 update·batchUpdate·execute |
| HTTP | RestOperations/RestTemplate·RestClient·WebClient의 exchange·retrieve·getForObject·getForEntity·postForObject·postForEntity·put·patch·delete 등, JDK HttpClient의 send/sendAsync, Apache HttpClient의 execute |
| Feign | `@FeignClient` 타입의 abstract 인스턴스 메서드. default/static helper는 제외 |
| 메시지 | KafkaOperations/KafkaTemplate·AmqpTemplate/RabbitTemplate·JmsOperations/JmsTemplate의 send/convertAndSend 등, ApplicationEventPublisher의 publishEvent |
| 프로세스·파일 | Runtime.exec·ProcessBuilder.start, Files의 쓰기·삭제·복사·이동, OutputStream/Writer.write |

호출 해석이 실패한 경우에만 기존 수신 객체·메서드 이름 추정을 사용한다. 부수 효과를 사용하는 `TX-003~005`, `IDEM-001~004`, `REL-001` 결과에는 `SIDE_EFFECT_CLASSIFICATION` 증거를 추가한다. `basis=RESOLVED_TYPE`은 해석된 시그니처를 포함하고, `basis=NAME_HEURISTIC`은 이름 추정의 부정확성 가능성을 명시한다. 결과의 `REVIEW` 상태와 기존 CI 실패 정책은 유지한다.

## 한계

- 지원 목록은 모든 라이브러리·커스텀 wrapper를 포함하지 않는다. 해석에 성공한 미지원 API는 이름 추정으로 다시 분류하지 않으므로 후보가 누락될 수 있다.
- 외부 JAR은 심볼 해석용이다. JAR 내부 구현, 실제 HTTP 전송·DB 변경, annotation 기반 proxy의 실행 의미를 분석하지 않는다. JDBC execute나 HTTP retrieve도 실행된 부수 효과의 증명이 아닌 후보 판정이다.
- 일부 의존 타입·상속 정보가 없으면 알려진 API를 연결하지 못할 수 있다. 해석 실패 시 이름이 임의인 객체는 기존 이름 추정으로도 탐지되지 않을 수 있다.
- 실제 트랜잭션 경계, 동시 실행 안전성, 멱등성, 재시도·부분 실패 안전성을 실행으로 증명하지 않는다.
