package com.dochiri.ilsim.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TaskServiceTest {

    private final TaskService.Project demo = new TaskService.Project("demo", "demo", null);
    private TaskService service;
    private Task task;

    @BeforeEach
    void setUp() {
        service = new TaskService(new RuleModelRouter());
        service.poll("r1", List.of(demo));
        task = service.create("demo", "slug", "slugify 추가");
    }

    private TaskService.StageResult pass(Map<String, String> artifacts) {
        return new TaskService.StageResult(true, false, "diff", null, artifacts, Map.of(), "b");
    }

    private TaskService.StageResult fail(boolean retryable) {
        return new TaskService.StageResult(false, retryable, null, "boom", null, Map.of(), "b");
    }

    @Test
    void 등록되지_않은_프로젝트나_잘못된_feature는_거절한다() {
        assertThatThrownBy(() -> service.create("none", "a", "x")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.create("demo", "Bad Name", "x")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 승인_단계는_승인_전에는_다음_단계로_넘어가지_않는다() {
        var a = service.poll("r1", List.of(demo)).orElseThrow();
        assertThat(a.stage()).isEqualTo("REQUIREMENTS");
        service.report(task.id, pass(Map.of("specs/slug/requirements.md", "REQ-1")));

        assertThat(task.current().status).isEqualTo(StageState.Status.WAITING_APPROVAL);
        assertThat(task.current().artifactHash).isNotBlank();
        assertThat(service.poll("r1", List.of(demo))).isEmpty();

        service.approve(task.id);
        assertThat(service.poll("r1", List.of(demo)).orElseThrow().stage()).isEqualTo("TEST_CASES");
    }

    @Test
    void 거절하면_피드백과_함께_같은_단계를_다시_실행한다() {
        service.poll("r1", List.of(demo));
        service.report(task.id, pass(Map.of()));
        service.reject(task.id, "예외 상황 보강");

        var a = service.poll("r1", List.of(demo)).orElseThrow();
        assertThat(a.stage()).isEqualTo("REQUIREMENTS");
        assertThat(a.prompt()).contains("예외 상황 보강");
    }

    @Test
    void 재시도_가능한_실패는_최대_횟수까지_재시도하고_이후_차단한다() {
        for (int i = 1; i <= TaskService.MAX_ATTEMPTS; i++) {
            assertThat(service.poll("r1", List.of(demo))).isPresent();
            service.report(task.id, fail(true));
        }
        assertThat(task.status).isEqualTo(Task.Status.BLOCKED);
        assertThat(service.poll("r1", List.of(demo))).isEmpty();

        service.retry(task.id);
        assertThat(task.status).isEqualTo(Task.Status.ACTIVE);
        assertThat(service.poll("r1", List.of(demo))).isPresent();
    }

    @Test
    void 재시도_불가_실패는_즉시_차단한다() {
        service.poll("r1", List.of(demo));
        service.report(task.id, fail(false));
        assertThat(task.status).isEqualTo(Task.Status.BLOCKED);
    }

    @Test
    void 마지막_단계까지_통과하면_완료된다() {
        service.poll("r1", List.of(demo));
        service.report(task.id, pass(Map.of()));
        service.approve(task.id);
        service.poll("r1", List.of(demo));
        service.report(task.id, pass(Map.of()));
        service.approve(task.id);
        service.poll("r1", List.of(demo));
        service.report(task.id, pass(Map.of()));
        service.poll("r1", List.of(demo));
        service.report(task.id, pass(Map.of()));

        assertThat(task.status).isEqualTo(Task.Status.COMPLETED);
    }

    @Test
    void 시도가_늘면_상위_모델로_올린다() {
        assertThat(new RuleModelRouter().route(task, Stage.IMPLEMENTATION, 1)).isEqualTo("sonnet");
        assertThat(new RuleModelRouter().route(task, Stage.IMPLEMENTATION, 3)).isEqualTo("opus");
    }

    private TaskService.StageResult failWith(String error) {
        return new TaskService.StageResult(false, true, null, error, null, Map.of(), "b");
    }

    private void toImplementation() {
        service.poll("r1", List.of(demo));
        service.report(task.id, pass(Map.of()));
        service.approve(task.id);
        service.poll("r1", List.of(demo));
        service.report(task.id, pass(Map.of()));
        service.approve(task.id);
        service.poll("r1", List.of(demo));
        service.report(task.id, pass(Map.of()));
    }

    @Test
    void 같은_사유로_연달아_실패하면_남은_시도를_건너뛰고_상위_모델로_올린다() {
        toImplementation();
        var first = service.poll("r1", List.of(demo)).orElseThrow();
        assertThat(first.stage()).isEqualTo("IMPLEMENTATION");
        service.report(task.id, failWith("Foo.kt:12 테스트 실패: expected 3"));

        var second = service.poll("r1", List.of(demo)).orElseThrow();
        assertThat(second.attempt()).isEqualTo(2);
        service.report(task.id, failWith("Foo.kt:48 테스트 실패: expected 7"));

        var third = service.poll("r1", List.of(demo)).orElseThrow();
        assertThat(third.attempt()).isEqualTo(TaskService.MAX_ATTEMPTS);
        assertThat(third.model()).isEqualTo("claude-opus-5-5");
        assertThat(third.effort()).isEqualTo("medium");
    }

    @Test
    void 사유가_달라지면_차례대로_시도한다() {
        toImplementation();
        service.poll("r1", List.of(demo));
        service.report(task.id, failWith("테스트 실패"));
        service.poll("r1", List.of(demo));
        service.report(task.id, failWith("아키텍처 위반"));
        assertThat(service.poll("r1", List.of(demo)).orElseThrow().attempt()).isEqualTo(3);
    }

    @Test
    void 사람이_개입하지_않으면_작업_전체_실행_횟수_상한에서_멈추고_개입하면_초기화된다() {
        for (int i = 0; i < TaskService.MAX_TASK_RUNS; i++) {
            service.poll("r1", List.of(demo));
            service.report(task.id, pass(Map.of()));
            service.reject(task.id, "다시"); // 거절은 사람의 개입이라 횟수가 초기화된다
        }
        assertThat(task.runs).isZero();

        task.runs = TaskService.MAX_TASK_RUNS;
        assertThat(service.poll("r1", List.of(demo))).isEmpty();
        assertThat(task.status).isEqualTo(Task.Status.BLOCKED);
        assertThat(task.current().error).contains("상한");

        service.retry(task.id);
        assertThat(task.runs).isZero();
        assertThat(service.poll("r1", List.of(demo))).isPresent();
    }
}
