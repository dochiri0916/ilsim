package com.dochiri.ilsim.task;

import java.util.List;
import java.util.Set;

/**
 * Agent 가 쓸 수 있는 Claude 모델과 추론 강도. 라우터는 등급(haiku, sonnet, opus)만 고르고,
 * 실제 실행에는 5.5 세대의 모델 ID 로 고정해 보낸다(별칭은 최신 모델로 바뀔 수 있어 쓰지 않는다).
 */
final class Models {

    static final List<String> TIERS = List.of("haiku", "sonnet", "opus");

    private static final Set<String> EFFORTS = Set.of("low", "medium", "high", "xhigh", "max");

    private Models() {}

    static String id(String tier) {
        return switch (tier) {
            case "haiku" -> "claude-haiku-5-5";
            case "sonnet" -> "claude-sonnet-5-5";
            case "opus" -> "claude-opus-5-5";
            default -> throw new IllegalArgumentException("알 수 없는 모델 등급: " + tier);
        };
    }

    /** 기본 추론 강도는 medium. 환경 변수 ILSIM_EFFORT 로 바꿀 수 있다. */
    static String effort() {
        String configured = System.getenv("ILSIM_EFFORT");
        return configured != null && EFFORTS.contains(configured.trim().toLowerCase()) ? configured.trim().toLowerCase() : "medium";
    }
}
