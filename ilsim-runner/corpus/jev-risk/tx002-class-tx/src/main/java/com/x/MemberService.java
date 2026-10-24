package com.x;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
@RequiredArgsConstructor
public class MemberService {
    private final MemberRepository memberRepository;
    private final MemberHistoryRepository historyRepository;

    public void register(String email) {
        String normalized = email.trim().toLowerCase();
        saveWithHistory(normalized);
    }

    @Transactional
    public void saveWithHistory(String email) {
        Member member = memberRepository.save(new Member(email));
        historyRepository.save(new MemberHistory(member.getId(), "REGISTERED"));
    }
}
