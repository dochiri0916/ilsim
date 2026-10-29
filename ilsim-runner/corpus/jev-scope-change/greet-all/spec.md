# 여러 사람 한 번에 인사

## 요구사항
- R1: Greeter에 greetAll(List<String> names) 공개 메서드를 추가하고, 이름마다 "Hello, <이름>." 문구를 입력 순서대로 담은 목록을 반환한다.
- R2: 인사말 Hello는 메서드 안 문자열 리터럴로 쓰지 않고 demo.Messages 클래스의 public static final String 상수로 둔다.
- R3: 각 이름은 demo.Names.normalize(String)로 앞뒤 공백을 지우고, 지운 이름이 비어 있으면 IllegalArgumentException("name must not be blank")을 던지며, 이 동작을 단위 테스트로 검증한다.
- R4: 테스트 메서드마다 @DisplayName으로 한국어 설명을 붙이고, 정상 문구와 빈 이름 예외를 각각 별도 테스트로 검증한다.
- R5: greetAll의 정상 동작과 빈 이름 예외를 단위 테스트로 검증하고 ./gradlew test가 통과한다.

## 영향 범위
- `src/main/java/demo/Greeter.java`

## 기존 기능 영향
- 기존 greet(String)의 동작은 바꾸지 않는다.
