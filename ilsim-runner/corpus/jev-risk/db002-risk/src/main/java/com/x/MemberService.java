package com.x;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class MemberService {
    private final MemberRepository memberRepository;

    @Transactional
    public Long signUp(String email, String name) {
        if (memberRepository.existsByEmail(email)) {
            throw new IllegalArgumentException("이미 가입한 이메일");
        }
        Member member = memberRepository.save(new Member(email, name));
        return member.getId();
    }
}
