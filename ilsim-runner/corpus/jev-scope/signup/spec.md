# 회원 가입 검증

## 요구사항
- R1: 이미 가입한 이메일로 가입하면 DuplicateEmailException을 던지고 HTTP 409로 응답한다.
- R2: 비밀번호가 8자 미만이면 가입을 거부하고 HTTP 400으로 응답한다. 8자는 허용한다.
- R3: 가입이 끝나면 MemberJoinedEvent를 발행한다.

## 영향 범위
- `src/main/java/com/x/member/MemberService.java`
- `src/test/java/com/x/member/MemberServiceTest.java`

## 기존 기능 영향
- 로그인과 회원 조회는 바꾸지 않는다.
