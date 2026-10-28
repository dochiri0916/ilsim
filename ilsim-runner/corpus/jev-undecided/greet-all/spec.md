# 여러 사람 한 번에 인사

## 배경
`demo.Greeter`에는 `greet(String name)`만 있고 `"Hello, " + name`을 반환한다(마침표 없음, 이름 검증 없음). 여러 사람에게 한 번에 인사하는 `greetAll(names)` 메서드를 추가한다.

## 요구사항

### REQ-1 greetAll 메서드 추가
- `demo.Greeter`에 `greetAll(names)` 공개 메서드를 추가한다. 인자는 이름 목록이다.
- 인수 조건: 목록의 모든 이름이 입력 순서대로 담긴 인사 결과를 반환한다.

### REQ-2 문구 형식
- 각 이름의 인사 문구는 `Hello, <이름>.` 형식이다.

### REQ-3 문구 상수
- 인사말 `Hello`는 `demo.Messages`의 `public static final String HELLO` 상수로 둔다.

### REQ-4 이름 검증·정규화
- 각 이름은 `demo.Names.normalize(String)`로 앞뒤 공백을 지운다. 지운 결과가 비어 있으면 `IllegalArgumentException("name must not be blank")`를 던진다.

## 영향 범위
- `src/main/java/demo/Greeter.java`
- `src/main/java/demo/Messages.java` (새 파일)
- `src/main/java/demo/Names.java` (새 파일)
- `src/test/java/demo/GreeterTest.java`

## 미결정 사항
- Q1. 반환 형태: 이름별 문구 목록(`List<String>`)인가, 하나의 문자열로 합치는가? 합친다면 구분자는?
- Q2. 빈 목록이 들어오면 빈 결과를 반환하는가, 예외를 던지는가?
- Q3. `names`가 `null`이거나 원소에 `null`이 있으면 어떻게 처리하는가?
- Q4. 기존 `greet`도 이번에 REQ-2·REQ-4 형식(마침표, 공백 제거, 검증)에 맞게 고치는가?
- Q5. 인자 타입은 `List<String>`인가, 가변 인자 `String...`인가?
