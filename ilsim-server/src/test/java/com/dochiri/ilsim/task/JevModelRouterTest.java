package com.dochiri.ilsim.task;

import static org.assertj.core.api.Assertions.assertThat;

import com.dochiri.ilsim.jev.JevClient;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class JevModelRouterTest {

    private static class FakeJev extends JevClient {
        final AtomicInteger calls = new AtomicInteger();
        final Double complexity;
        final Double risk;

        FakeJev(Double complexity, Double risk) {
            super((io.github.gudcks0305.jev.JevClient) null);
            this.complexity = complexity;
            this.risk = risk;
        }

        @Override
        public boolean enabled() {
            return complexity != null;
        }

        @Override
        public Optional<Map<String, Answer>> evaluate(Object state, Map<String, Question> questions) {
            calls.incrementAndGet();
            if (complexity == null) return Optional.empty();
            return Optional.of(Map.of(
                    "complexity", new Answer("score", null, null, Map.of(), complexity),
                    "risk", new Answer("score", null, null, Map.of(), risk)));
        }
    }

    private final Task task = new Task("demo", "slug", "주문 취소 시 재고를 복원한다");

    private JevModelRouter router(FakeJev jev, String min) {
        return new JevModelRouter(jev, new RuleModelRouter(), min);
    }

    @Test
    void 정책은_평가_최소_모델_재시도로_등급을_정한다() {
        assertThat(JevModelRouter.choose(0.5, 0.2, 1, "haiku")).isEqualTo("haiku");
        assertThat(JevModelRouter.choose(0.5, 0.2, 1, "sonnet")).isEqualTo("sonnet");
        assertThat(JevModelRouter.choose(1.8, 0.2, 1, "haiku")).isEqualTo("sonnet");
        assertThat(JevModelRouter.choose(0.5, 1.7, 1, "haiku")).isEqualTo("opus");
        assertThat(JevModelRouter.choose(0.5, 0.2, 2, "haiku")).isEqualTo("sonnet");
        assertThat(JevModelRouter.choose(0.5, 0.2, 3, "haiku")).isEqualTo("opus");
        assertThat(JevModelRouter.choose(0.5, 0.2, 2, "sonnet")).isEqualTo("opus");
    }

    @Test
    void 쉬운_작업은_최소_모델로_위험한_작업은_상위_모델로_보내고_이력을_남긴다() {
        assertThat(router(new FakeJev(0.4, 0.1), "haiku").route(task, Stage.IMPLEMENTATION, 1)).isEqualTo("haiku");
        Task risky = new Task("demo", "slug", "결제 취소");
        var router = router(new FakeJev(2.8, 1.9), "sonnet");
        assertThat(router.route(risky, Stage.IMPLEMENTATION, 1)).isEqualTo("opus");
        StageState s = risky.stages.stream().filter(x -> x.stage == Stage.IMPLEMENTATION).findFirst().orElseThrow();
        assertThat(s.routing).containsEntry("source", "jev").containsEntry("model", "opus");
        assertThat(s.routingLog.getFirst()).contains("시도 1", "opus", "JEV");
    }

    @Test
    void 같은_단계의_재시도는_평가를_다시_하지_않는다() {
        var jev = new FakeJev(0.4, 0.1);
        var router = router(jev, "haiku");
        assertThat(router.route(task, Stage.IMPLEMENTATION, 1)).isEqualTo("haiku");
        assertThat(router.route(task, Stage.IMPLEMENTATION, 2)).isEqualTo("sonnet");
        assertThat(router.route(task, Stage.IMPLEMENTATION, 3)).isEqualTo("opus");
        assertThat(jev.calls.get()).isEqualTo(1);
    }

    @Test
    void JEV_를_쓸_수_없으면_규칙_기반으로_되돌아간다() {
        var router = router(new FakeJev(null, null), "sonnet");
        assertThat(router.route(task, Stage.IMPLEMENTATION, 1)).isEqualTo("sonnet");
        assertThat(router.route(task, Stage.IMPLEMENTATION, 3)).isEqualTo("opus");
        StageState s = task.stages.stream().filter(x -> x.stage == Stage.IMPLEMENTATION).findFirst().orElseThrow();
        assertThat(s.routingLog.getFirst()).contains("규칙 기반", "키 없음");
    }
}
