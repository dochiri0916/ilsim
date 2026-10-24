package com.x;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ProfileService {
    private final MemberRepository memberRepository;

    @Transactional(readOnly = true)
    public ProfileView view(Long memberId) {
        Member member = memberRepository.findById(memberId).orElseThrow();
        member.touchLastViewed();
        memberRepository.save(member);
        return new ProfileView(member.getName());
    }
}
