package com.x;

import lombok.RequiredArgsConstructor;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
public class GradeEventConsumer {
    private final MemberRepository memberRepository;

    @KafkaListener(topics = "grade-changed", groupId = "member")
    @Transactional
    public void onGradeChanged(GradeChanged event) {
        Member member = memberRepository.findById(event.memberId()).orElseThrow();
        member.changeGrade(event.grade());
    }
}
