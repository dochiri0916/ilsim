package com.x;

import java.util.concurrent.locks.Lock;
import lombok.RequiredArgsConstructor;
import org.springframework.integration.redis.util.RedisLockRegistry;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class MemberService {
    private final MemberRepository memberRepository;
    private final RedisLockRegistry lockRegistry;

    @Transactional
    public Long signUp(String email, String name) {
        Lock lock = lockRegistry.obtain("member:signup:" + email);
        lock.lock();
        try {
            if (memberRepository.existsByEmail(email)) {
                throw new IllegalArgumentException("이미 가입한 이메일");
            }
            return memberRepository.save(new Member(email, name)).getId();
        } finally {
            lock.unlock();
        }
    }
}
