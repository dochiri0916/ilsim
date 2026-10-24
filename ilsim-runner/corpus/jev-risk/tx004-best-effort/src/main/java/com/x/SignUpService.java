package com.x;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class SignUpService {
    private static final Logger log = LoggerFactory.getLogger(SignUpService.class);
    private final MemberRepository memberRepository;
    private final SignUpMetrics signUpMetrics;

    @Transactional
    public Long signUp(String email) {
        Member member = memberRepository.save(new Member(email));
        try {
            signUpMetrics.recordInMemory(member.getEmail());
        } catch (Exception e) {
            log.warn("가입 메트릭 기록 실패", e);
        }
        return member.getId();
    }
}
