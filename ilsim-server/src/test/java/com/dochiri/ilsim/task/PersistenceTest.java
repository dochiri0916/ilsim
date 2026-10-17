package com.dochiri.ilsim.task;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class PersistenceTest {

    /** 저장소 구현을 흉내 내는 메모리 저장소. 저장할 때 JSON 으로 직렬화해 실제 저장과 같은 왕복을 거친다. */
    private static class MemoryStore implements TaskStore {
        final JsonMapper json = JsonMapper.builder().build();
        final Map<String, String> tasks = new LinkedHashMap<>();
        final Map<String, List<Task.Event>> events = new LinkedHashMap<>();
        final Map<String, ProjectRecord> projects = new LinkedHashMap<>();
        boolean down;
        int projectWrites;

        private void check() {
            if (down) throw new IllegalStateException("db down");
        }

        @Override public List<TaskSnapshot> loadTasks() {
            check();
            return tasks.values().stream().map(j -> json.readValue(j, TaskSnapshot.class)).toList();
        }
        @Override public void saveTask(TaskSnapshot t) { check(); tasks.put(t.id(), json.writeValueAsString(t)); }
        @Override public List<Task.Event> loadEvents(String id, int limit) { check(); return new ArrayList<>(events.getOrDefault(id, List.of())); }
        @Override public void appendEvent(String id, Task.Event e) { check(); events.computeIfAbsent(id, k -> new ArrayList<>()).add(e); }
        @Override public Map<String, ProjectRecord> loadProjects() { check(); return projects; }
        @Override public void saveProject(String id, ProjectRecord p) { check(); projects.put(id, p); projectWrites++; }
    }

    private final TaskService.Project demo = new TaskService.Project("demo", "demo", null);

    private TaskService service(MemoryStore store) {
        TaskService s = new TaskService(new RuleModelRouter(), store);
        s.restore();
        return s;
    }

    @Test
    void 작업과_단계_결과_이벤트가_저장되고_서버를_다시_띄워도_되살아난다() {
        var store = new MemoryStore();
        var first = service(store);
        first.poll("r1", List.of(demo));
        Task task = first.create("demo", "slug", "slugify 추가");
        first.poll("r1", List.of(demo));
        first.addEvent(task.id, "REQUIREMENTS", "log", "시작");
        first.report(task.id, new TaskService.StageResult(true, false, "diff", null,
                Map.of("specs/slug/requirements.md", "REQ-1"), Map.of("traceability", "PASSED"), "branch-1"));
        first.approve(task.id);

        var second = service(store);
        Task restored = second.find(task.id).orElseThrow();
        assertThat(restored.feature).isEqualTo("slug");
        assertThat(restored.requirement).isEqualTo("slugify 추가");
        assertThat(restored.createdAt).isEqualTo(task.createdAt);
        assertThat(restored.branch).isEqualTo("branch-1");
        StageState requirements = restored.stages.getFirst();
        assertThat(requirements.status).isEqualTo(StageState.Status.PASSED);
        assertThat(requirements.artifacts).containsEntry("specs/slug/requirements.md", "REQ-1");
        assertThat(requirements.validation).containsEntry("traceability", "PASSED");
        assertThat(restored.current().stage).isEqualTo(Stage.TEST_CASES);
        assertThat(second.events(task.id)).extracting(Task.Event::data).containsExactly("시작");
        assertThat(second.projects()).extracting(TaskService.ProjectView::id).containsExactly("demo");
    }

    @Test
    void 실행_중이던_단계는_대기로_되돌려_다시_배정한다() {
        var store = new MemoryStore();
        var first = service(store);
        first.poll("r1", List.of(demo));
        Task task = first.create("demo", "slug", "x");
        assertThat(first.poll("r1", List.of(demo))).isPresent();
        assertThat(task.current().status).isEqualTo(StageState.Status.RUNNING);

        var second = service(store);
        assertThat(second.find(task.id).orElseThrow().current().status).isEqualTo(StageState.Status.PENDING);
        assertThat(second.poll("r1", List.of(demo))).isPresent();
    }

    @Test
    void 저장소가_죽어도_메모리로_계속_동작하고_시작할_때_못_읽으면_빈_상태로_뜬다() {
        var store = new MemoryStore();
        store.down = true;
        var service = service(store);
        assertThat(service.list()).isEmpty();
        service.poll("r1", List.of(demo));
        Task task = service.create("demo", "slug", "x");
        assertThat(service.poll("r1", List.of(demo))).isPresent();
        service.addEvent(task.id, "REQUIREMENTS", "log", "계속");
        assertThat(service.events(task.id)).hasSize(1);
        assertThat(store.tasks).isEmpty();
    }

    @Test
    void Runner_가_매번_poll_해도_프로젝트는_30초에_한_번만_저장하고_최근에_본_프로젝트만_online_이다() {
        var store = new MemoryStore();
        var service = service(store);
        for (int i = 0; i < 5; i++) service.poll("r1", List.of(demo));
        assertThat(store.projectWrites).isEqualTo(1);
        assertThat(service.projects().getFirst().online()).isTrue();

        store.projects.put("old", new TaskStore.ProjectRecord("old", "r0", Instant.now().minusSeconds(3600)));
        var restarted = service(store);
        assertThat(restarted.projects()).filteredOn(p -> p.id().equals("old")).singleElement().satisfies(p -> assertThat(p.online()).isFalse());
    }
}
