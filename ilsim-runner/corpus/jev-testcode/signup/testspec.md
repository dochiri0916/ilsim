# 회원 가입 — 테스트 케이스

- TC-1 (REQ-1, 정상): 올바른 요청으로 `POST /members` → 201과 회원 id
- TC-2 (REQ-3, 정상): 가입하면 비밀번호가 해시로 저장되고 `PasswordEncoder.matches`로 맞는다
- TC-3 (REQ-4, 정상): 가입하면 `MemberJoinedEvent`가 한 번 발행된다
- TC-4 (REQ-2, 예외): 같은 이메일로 다시 가입하면 `DuplicateEmailException`
