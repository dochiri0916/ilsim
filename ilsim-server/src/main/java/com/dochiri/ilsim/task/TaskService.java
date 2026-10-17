package com.dochiri.ilsim.task;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class TaskService {

    static final int MAX_ATTEMPTS = 3;
    /** 사람이 개입하지 않은 채 한 작업에 배정할 수 있는 전체 횟수(단계 4개 × 시도 3회). */
    static final int MAX_TASK_RUNS = 12;
    private static final Pattern FEATURE = Pattern.compile("[a-z0-9][a-z0-9-]{0,40}");

    public record Project(String id, String name, String runnerId) {}

    /** 웹에 보여 주는 프로젝트. Runner 가 최근에 poll 했으면 online. */
    public record ProjectView(String id, String name, String runnerId, Instant lastSeen, boolean online) {}

    private static final Logger log = LoggerFactory.getLogger(TaskService.class);
    private static final long ONLINE_SECONDS = 15;
    private static final long PROJECT_SAVE_SECONDS = 30;

    public record Assignment(String taskId, String projectId, String stage, int attempt,
                             String feature, String model, String effort, String prompt) {}

    public record StageResult(boolean passed, boolean retryable, String diff, String error,
                              Map<String, String> artifacts, Map<String, String> validation, String branch) {}

    private final Map<String, Task> tasks = new ConcurrentHashMap<>();
    private final Map<String, List<Task.Event>> events = new ConcurrentHashMap<>();
    private final Map<String, Project> projects = new ConcurrentHashMap<>();
    private final Map<String, Instant> seen = new ConcurrentHashMap<>();
    private final Map<String, Instant> savedSeen = new ConcurrentHashMap<>();
    private final ModelRouter router;
    private final TaskStore store;

    @Autowired
    TaskService(ModelRouter router, TaskStore store) {
        this.router = router;
        this.store = store;
    }

    /** 저장소 없이(메모리만) 쓴다. 단위 테스트용. */
    public TaskService(ModelRouter router) {
        this(router, TaskStore.NONE);
    }

    /** 저장된 작업과 프로젝트를 되살린다. 실행 중이던 단계는 Runner 가 사라졌으므로 대기로 되돌린다. 저장소를 못 읽으면 빈 상태로 시작한다. */
    @PostConstruct
    void restore() {
        try {
            store.loadProjects().forEach((id, p) -> {
                projects.put(id, new Project(id, p.name(), p.runnerId()));
                seen.put(id, p.lastSeen());
                savedSeen.put(id, p.lastSeen());
            });
            for (TaskSnapshot snapshot : store.loadTasks()) {
                Task task = snapshot.restore();
                tasks.put(task.id, task);
            }
            if (!tasks.isEmpty()) log.info("저장된 작업 {}건을 되살렸습니다", tasks.size());
        } catch (RuntimeException e) {
            log.warn("저장된 작업을 읽지 못해 빈 상태로 시작합니다: {}", e.getMessage());
        }
    }

    private void persist(Task task) {
        try {
            store.saveTask(TaskSnapshot.of(task));
        } catch (RuntimeException e) {
            log.warn("작업 저장에 실패했습니다(메모리에서는 계속 동작): {}", e.getMessage());
        }
    }

    public Task create(String projectId, String feature, String requirement) {
        if (!projects.containsKey(projectId)) {
            throw new IllegalArgumentException("등록되지 않은 프로젝트: " + projectId);
        }
        if (requirement == null || requirement.isBlank()) {
            throw new IllegalArgumentException("요구사항이 비어 있습니다");
        }
        if (feature != null && !feature.isBlank() && !FEATURE.matcher(feature).matches()) {
            throw new IllegalArgumentException("feature는 소문자·숫자·하이픈만 사용할 수 있습니다");
        }
        Task task = new Task(projectId, feature, requirement);
        tasks.put(task.id, task);
        events.put(task.id, Collections.synchronizedList(new ArrayList<>()));
        persist(task);
        return task;
    }

    public Optional<Task> find(String id) {
        return Optional.ofNullable(tasks.get(id));
    }

    public List<Task> list() {
        return tasks.values().stream().sorted(Comparator.comparing((Task t) -> t.createdAt).reversed()).toList();
    }

    public List<ProjectView> projects() {
        Instant now = Instant.now();
        return projects.values().stream().sorted(Comparator.comparing(Project::id)).map(p -> {
            Instant last = seen.get(p.id());
            return new ProjectView(p.id(), p.name(), p.runnerId(), last, last != null && now.minusSeconds(ONLINE_SECONDS).isBefore(last));
        }).toList();
    }

    /** 이벤트 목록. 서버를 다시 띄운 뒤 처음 열 때 저장소에서 최근 것을 읽어 온다. */
    private List<Task.Event> eventList(String taskId) {
        if (!tasks.containsKey(taskId)) return null;
        return events.computeIfAbsent(taskId, id -> {
            try {
                return Collections.synchronizedList(new ArrayList<>(store.loadEvents(id, 1000)));
            } catch (RuntimeException e) {
                log.warn("이벤트를 읽지 못했습니다: {}", e.getMessage());
                return Collections.synchronizedList(new ArrayList<>());
            }
        });
    }

    public List<Task.Event> events(String taskId) {
        List<Task.Event> list = eventList(taskId);
        if (list == null) return List.of();
        synchronized (list) {
            return new ArrayList<>(list);
        }
    }

    /** Runner가 보고한 프로젝트를 등록하고, 실행 대기 중인 단계 하나를 RUNNING으로 넘긴다. */
    public synchronized Optional<Assignment> poll(String runnerId, List<Project> reported) {
        Set<String> ids = new HashSet<>();
        Instant now = Instant.now();
        reported.forEach(p -> {
            Project previous = projects.put(p.id(), new Project(p.id(), p.name(), runnerId));
            seen.put(p.id(), now);
            ids.add(p.id());
            Instant saved = savedSeen.get(p.id());
            boolean changed = previous == null || !previous.name().equals(p.name()) || !Objects.equals(previous.runnerId(), runnerId);
            if (changed || saved == null || saved.plusSeconds(PROJECT_SAVE_SECONDS).isBefore(now)) {
                try {
                    store.saveProject(p.id(), new TaskStore.ProjectRecord(p.name(), runnerId, now));
                    savedSeen.put(p.id(), now);
                } catch (RuntimeException e) {
                    log.warn("프로젝트 저장에 실패했습니다: {}", e.getMessage());
                }
            }
        });
        tasks.values().stream()
                .filter(t -> t.status == Task.Status.ACTIVE && t.runs >= MAX_TASK_RUNS && t.current() != null
                        && t.current().status == StageState.Status.PENDING)
                .forEach(t -> {
                    StageState s = t.current();
                    s.status = StageState.Status.FAILED;
                    s.error = "작업 전체 실행 횟수 상한(" + MAX_TASK_RUNS + ")에 도달해 멈췄습니다";
                    t.status = Task.Status.BLOCKED;
                    persist(t);
                });
        return tasks.values().stream()
                .filter(t -> t.status == Task.Status.ACTIVE && ids.contains(t.projectId))
                .filter(t -> t.current() != null && t.current().status == StageState.Status.PENDING)
                .min(Comparator.comparing(t -> t.createdAt))
                .map(t -> {
                    StageState s = t.current();
                    s.status = StageState.Status.RUNNING;
                    t.runnerId = runnerId;
                    t.runs++;
                    Assignment assignment = new Assignment(t.id, t.projectId, s.stage.name(), s.attempt, t.feature,
                            Models.id(router.route(t, s.stage, s.attempt)), Models.effort(), PromptTemplates.build(t, s.stage, s.feedback));
                    persist(t);
                    return assignment;
                });
    }

    public void addEvent(String taskId, String stage, String type, String data) {
        List<Task.Event> list = eventList(taskId);
        if (list == null) return;
        Task.Event event = new Task.Event(Instant.now(), stage, type, data);
        list.add(event);
        try {
            store.appendEvent(taskId, event);
        } catch (RuntimeException e) {
            log.warn("이벤트 저장에 실패했습니다: {}", e.getMessage());
        }
    }

    public synchronized void report(String taskId, StageResult r) {
        Task task = require(taskId);
        StageState s = task.current();
        if (s == null || s.status != StageState.Status.RUNNING) {
            throw new IllegalStateException("실행 중인 단계가 없습니다");
        }
        if (r.branch() != null) task.branch = r.branch();
        s.diff = r.diff();
        s.error = r.error();
        s.validation = r.validation() == null ? new LinkedHashMap<>() : r.validation();
        if (r.passed()) {
            s.artifacts = r.artifacts() == null ? new LinkedHashMap<>() : r.artifacts();
            s.artifactHash = hash(s.artifacts);
            s.feedback = null;
            s.lastErrorKey = null;
            if (s.stage.approval) {
                s.status = StageState.Status.WAITING_APPROVAL;
            } else {
                s.status = StageState.Status.PASSED;
                finishIfDone(task);
            }
        } else if (r.retryable() && s.attempt < MAX_ATTEMPTS) {
            // 같은 사유로 연달아 실패하면 같은 모델로 되풀이해도 달라지지 않는다. 남은 시도를 건너뛰고 마지막 시도(상위 모델)로 올린다.
            String key = errorKey(r.error());
            boolean repeated = key != null && key.equals(s.lastErrorKey);
            s.lastErrorKey = key;
            s.feedback = r.error();
            s.attempt = repeated ? MAX_ATTEMPTS : s.attempt + 1;
            s.status = StageState.Status.PENDING;
        } else {
            s.status = StageState.Status.FAILED;
            task.status = Task.Status.BLOCKED;
        }
        persist(task);
    }

    public synchronized void approve(String taskId) {
        Task task = require(taskId);
        StageState s = task.current();
        if (s == null || s.status != StageState.Status.WAITING_APPROVAL) {
            throw new IllegalStateException("승인 대기 중인 단계가 없습니다");
        }
        s.status = StageState.Status.PASSED;
        finishIfDone(task);
        persist(task);
    }

    public synchronized void reject(String taskId, String feedback) {
        Task task = require(taskId);
        StageState s = task.current();
        if (s == null || s.status != StageState.Status.WAITING_APPROVAL) {
            throw new IllegalStateException("승인 대기 중인 단계가 없습니다");
        }
        if (feedback == null || feedback.isBlank()) throw new IllegalArgumentException("거절 사유가 필요합니다");
        s.feedback = feedback;
        s.attempt = 1;
        s.lastErrorKey = null;
        task.runs = 0;
        s.status = StageState.Status.PENDING;
        persist(task);
    }

    /** 차단된 작업의 실패 단계를 처음부터 다시 시도한다. */
    public synchronized void retry(String taskId) {
        Task task = require(taskId);
        StageState s = task.current();
        if (task.status != Task.Status.BLOCKED || s == null) {
            throw new IllegalStateException("차단된 작업이 아닙니다");
        }
        s.attempt = 1;
        s.lastErrorKey = null;
        task.runs = 0;
        s.status = StageState.Status.PENDING;
        task.status = Task.Status.ACTIVE;
        persist(task);
    }

    /** 줄 번호·경로·숫자·공백 차이를 무시하고 사유의 앞부분만 비교한다. */
    static String errorKey(String error) {
        if (error == null || error.isBlank()) return null;
        String key = error.replaceAll("\\d+", "#").replaceAll("\\s+", " ").trim();
        return key.length() > 300 ? key.substring(0, 300) : key;
    }

    private void finishIfDone(Task task) {
        if (task.current() == null) task.status = Task.Status.COMPLETED;
    }

    private Task require(String id) {
        Task task = tasks.get(id);
        if (task == null) throw new NoSuchElementException("작업 없음: " + id);
        return task;
    }

    static String hash(Map<String, String> artifacts) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            new TreeMap<>(artifacts).forEach((k, v) -> {
                md.update(k.getBytes(StandardCharsets.UTF_8));
                md.update((byte) 0);
                md.update(v.getBytes(StandardCharsets.UTF_8));
                md.update((byte) 0);
            });
            return HexFormat.of().formatHex(md.digest());
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
