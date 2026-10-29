# 회원 가입

## 요구사항
- REQ-1: `POST /members`는 이메일·비밀번호·닉네임으로 회원을 만든다. 성공하면 201과 회원 id를 돌려준다.
- REQ-2: 이미 가입한 이메일이면 가입을 거절한다(`DuplicateEmailException`).
- REQ-3: 비밀번호는 `PasswordEncoder`(BCrypt)로 해시해 저장한다.
- REQ-4: 가입하면 `MemberJoinedEvent`를 발행한다.

## 영향 범위
- `src/main/kotlin/com/x/member/` 아래 `MemberController.kt`, `MemberService.kt`, `MemberRepository.kt`, `DuplicateEmailException.kt`, `MemberJoinedEvent.kt`, `JoinRequest.kt`
- `src/test/kotlin/com/x/member/` 아래 테스트

## 미결정 사항
- Q1. 이메일 중복을 확인할 때 대소문자를 구분하는가?
- Q2. 이메일 중복이면 HTTP 상태 코드는 409인가, 400인가?
- Q3. 비밀번호 길이·조합 규칙을 두는가?
- Q4. 닉네임 중복도 막는가?
