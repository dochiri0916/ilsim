package com.dochiri.ilsim.task;

import com.dochiri.ilsim.jev.JevClient;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/**
 * 작업의 난이도와 변경 위험도를 JEV 로 평가해 Claude 모델을 고른다.
 * JEV 는 평가만 하고 모델 선택은 이 클래스의 정책이 한다: 최소 모델 이상, 평가가 높을수록 상위 모델, 재시도할수록 한 단계씩 위로, 마지막 시도는 항상 최상위.
 * JEV 를 쓸 수 없으면 규칙 기반 라우터로 되돌아간다.
 */
@Component
@Primary
class JevModelRouter implements ModelRouter {

    static final List<String> TIERS = Models.TIERS;

    private static final JevClient.Question COMPLEXITY = JevClient.Question.score(
            "요구사항을 구현하는 작업의 난이도를 평가하라.",
            List.of("trivial: 한두 줄 수정", "simple: 한 파일 안의 작은 변경", "moderate: 여러 파일이나 새 로직", "complex: 여러 모듈이나 동시성·트랜잭션·보안과 얽힘"));
    private static final JevClient.Question RISK = JevClient.Question.score(
            "이 변경이 기존 동작을 깨뜨릴 위험을 평가하라.",
            List.of("low: 기존 동작에 영향 없음", "medium: 일부 기존 동작에 영향", "high: 결제·데이터·인증 같은 핵심 흐름에 영향"));

    private final JevClient jev;
    private final RuleModelRouter rules;
    private final String minModel;

    JevModelRouter(JevClient jev, RuleModelRouter rules, @Value("${ILSIM_MIN_MODEL:sonnet}") String minModel) {
        this.jev = jev;
        this.rules = rules;
        this.minModel = TIERS.contains(minModel) ? minModel : "sonnet";
    }

    @Override
    public String route(Task task, Stage stage, int attempt) {
        StageState s = task.stages.stream().filter(x -> x.stage == stage).findFirst().orElseThrow();
        Optional<double[]> assessment = assess(task, s, attempt);
        if (assessment.isEmpty()) {
            String model = rules.route(task, stage, attempt);
            s.routingLog.add("시도 " + attempt + ": " + model + " (규칙 기반" + (jev.enabled() ? ", JEV 평가 실패" : ", JEV 키 없음") + ")");
            return model;
        }
        double complexity = assessment.get()[0];
        double risk = assessment.get()[1];
        String model = choose(complexity, risk, attempt, minModel);
        s.routing = new LinkedHashMap<>(Map.of(
                "source", "jev", "complexity", String.format("%.2f", complexity), "risk", String.format("%.2f", risk), "model", model));
        s.routingLog.add(String.format("시도 %d: %s (JEV 난이도 %.2f/3, 위험 %.2f/2)", attempt, model, complexity, risk));
        return model;
    }

    /** 같은 단계의 평가는 한 번만 한다. 재시도에서는 저장된 값을 쓴다. */
    private Optional<double[]> assess(Task task, StageState s, int attempt) {
        Map<String, String> cached = s.routing;
        if (cached != null && "jev".equals(cached.get("source"))) {
            return Optional.of(new double[]{Double.parseDouble(cached.get("complexity")), Double.parseDouble(cached.get("risk"))});
        }
        if (!jev.enabled()) return Optional.empty();
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("stage", s.stage.name());
        state.put("attempt", attempt);
        state.put("requirement", clip(task.requirement, 4000));
        if (s.feedback != null) state.put("previousFailure", clip(s.feedback, 1500));
        return jev.evaluate(state, Map.of("complexity", COMPLEXITY, "risk", RISK)).flatMap(answers -> {
            JevClient.Answer c = answers.get("complexity");
            JevClient.Answer r = answers.get("risk");
            if (c == null || r == null || c.score() == null || r.score() == null) return Optional.empty();
            return Optional.of(new double[]{c.score(), r.score()});
        });
    }

    /** 정책: 평가로 기본 등급을 정하고, 최소 모델 아래로는 내리지 않으며, 재시도마다 한 단계씩 올린다. */
    static String choose(double complexity, double risk, int attempt, String minModel) {
        int tier = 0;
        if (complexity >= 1.5 || risk >= 0.8) tier = 1;
        if (complexity >= 2.5 || risk >= 1.5) tier = 2;
        tier = Math.max(tier, TIERS.indexOf(minModel));
        tier = Math.min(TIERS.size() - 1, tier + Math.max(0, attempt - 1));
        return TIERS.get(tier);
    }

    private static String clip(String text, int max) {
        return text == null ? "" : text.length() <= max ? text : text.substring(0, max);
    }
}
