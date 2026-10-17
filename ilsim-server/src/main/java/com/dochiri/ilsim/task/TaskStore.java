package com.dochiri.ilsim.task;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** 작업·이벤트·프로젝트 저장소. 구현이 실패해도 서버는 메모리로 계속 동작하며 저장만 건너뛴다. */
interface TaskStore {

    List<TaskSnapshot> loadTasks();

    void saveTask(TaskSnapshot task);

    List<Task.Event> loadEvents(String taskId, int limit);

    void appendEvent(String taskId, Task.Event event);

    /** 프로젝트 id → (이름, Runner id, 마지막으로 본 시각) */
    Map<String, ProjectRecord> loadProjects();

    void saveProject(String id, ProjectRecord project);

    record ProjectRecord(String name, String runnerId, Instant lastSeen) {}

    /** 저장하지 않는 구현. 단위 테스트와 DB 없는 실행에 쓴다. */
    TaskStore NONE = new TaskStore() {
        @Override public List<TaskSnapshot> loadTasks() { return List.of(); }
        @Override public void saveTask(TaskSnapshot task) {}
        @Override public List<Task.Event> loadEvents(String taskId, int limit) { return List.of(); }
        @Override public void appendEvent(String taskId, Task.Event event) {}
        @Override public Map<String, ProjectRecord> loadProjects() { return Map.of(); }
        @Override public void saveProject(String id, ProjectRecord project) {}
    };
}
