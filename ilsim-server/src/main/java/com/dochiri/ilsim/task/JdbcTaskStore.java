package com.dochiri.ilsim.task;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

/**
 * MySQL 저장소. 작업은 상태·시각만 컬럼으로 두고 본문은 JSON 사본 한 칼럼에 넣는다(단계 구조가 바뀌어도 마이그레이션이 필요 없다).
 * 테이블은 처음 쓸 때 만든다.
 */
@Repository
class JdbcTaskStore implements TaskStore {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final JdbcTemplate jdbc;
    private final AtomicBoolean ready = new AtomicBoolean(false);

    JdbcTaskStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private void ensureTables() {
        if (ready.get()) return;
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS tasks (
                  id         VARCHAR(36)  NOT NULL PRIMARY KEY,
                  project_id VARCHAR(100) NOT NULL,
                  status     VARCHAR(20)  NOT NULL,
                  created_at TIMESTAMP(3) NOT NULL,
                  updated_at TIMESTAMP(3) NOT NULL,
                  snapshot   LONGTEXT     NOT NULL,
                  KEY idx_tasks_created (created_at)
                ) CHARACTER SET utf8mb4""");
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS task_events (
                  id      BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
                  task_id VARCHAR(36)  NOT NULL,
                  at      TIMESTAMP(3) NOT NULL,
                  stage   VARCHAR(30),
                  type    VARCHAR(30),
                  data    MEDIUMTEXT,
                  KEY idx_events_task (task_id, id)
                ) CHARACTER SET utf8mb4""");
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS projects (
                  id        VARCHAR(100) NOT NULL PRIMARY KEY,
                  name      VARCHAR(200) NOT NULL,
                  runner_id VARCHAR(100),
                  last_seen TIMESTAMP(3) NOT NULL
                ) CHARACTER SET utf8mb4""");
        ready.set(true);
    }

    @Override
    public List<TaskSnapshot> loadTasks() {
        ensureTables();
        List<TaskSnapshot> out = new ArrayList<>();
        for (String json : jdbc.queryForList("SELECT snapshot FROM tasks ORDER BY created_at", String.class)) {
            out.add(JSON.readValue(json, TaskSnapshot.class));
        }
        return out;
    }

    @Override
    public void saveTask(TaskSnapshot task) {
        ensureTables();
        jdbc.update("""
                INSERT INTO tasks (id, project_id, status, created_at, updated_at, snapshot) VALUES (?, ?, ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE status = VALUES(status), updated_at = VALUES(updated_at), snapshot = VALUES(snapshot)""",
                task.id(), task.projectId(), task.status(), Timestamp.from(task.createdAt()), Timestamp.from(Instant.now()), JSON.writeValueAsString(task));
    }

    @Override
    public List<Task.Event> loadEvents(String taskId, int limit) {
        ensureTables();
        List<Task.Event> out = new ArrayList<>(jdbc.query(
                "SELECT at, stage, type, data FROM task_events WHERE task_id = ? ORDER BY id DESC LIMIT ?",
                (rs, i) -> new Task.Event(rs.getTimestamp(1).toInstant(), rs.getString(2), rs.getString(3), rs.getString(4)), taskId, limit));
        Collections.reverse(out);
        return out;
    }

    @Override
    public void appendEvent(String taskId, Task.Event event) {
        ensureTables();
        jdbc.update("INSERT INTO task_events (task_id, at, stage, type, data) VALUES (?, ?, ?, ?, ?)",
                taskId, Timestamp.from(event.at()), event.stage(), event.type(), event.data());
    }

    @Override
    public Map<String, ProjectRecord> loadProjects() {
        ensureTables();
        Map<String, ProjectRecord> out = new LinkedHashMap<>();
        jdbc.query("SELECT id, name, runner_id, last_seen FROM projects", rs -> {
            out.put(rs.getString(1), new ProjectRecord(rs.getString(2), rs.getString(3), rs.getTimestamp(4).toInstant()));
        });
        return out;
    }

    @Override
    public void saveProject(String id, ProjectRecord project) {
        ensureTables();
        jdbc.update("""
                INSERT INTO projects (id, name, runner_id, last_seen) VALUES (?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE name = VALUES(name), runner_id = VALUES(runner_id), last_seen = VALUES(last_seen)""",
                id, project.name(), project.runnerId(), Timestamp.from(project.lastSeen()));
    }
}
