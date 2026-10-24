package com.x;

import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ProfileService {
    private final MemberRepository memberRepository;
    private final ProfileViewRedisRepository profileViewRedisRepository;

    @Transactional(readOnly = true)
    public ProfileView view(Long memberId) {
        Member member = memberRepository.findById(memberId).orElseThrow();
        profileViewRedisRepository.save(new ProfileViewLog(memberId, Instant.now()));
        return new ProfileView(member.getName());
    }
}
