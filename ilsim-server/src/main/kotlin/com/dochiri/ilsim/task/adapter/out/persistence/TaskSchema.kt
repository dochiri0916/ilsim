package com.dochiri.ilsim.task.adapter.out.persistence

import java.util.concurrent.atomic.AtomicBoolean
import org.springframework.jdbc.core.JdbcTemplate

/** 테이블은 처음 쓸 때 만든다(서버가 뜰 때 DB 가 아직 없을 수 있다). */
class TaskSchema(private val jdbc: JdbcTemplate) {
    private val ready = AtomicBoolean(false)

    fun ensure() {
        if (ready.get()) return
        jdbc.execute(
            """
            CREATE TABLE IF NOT EXISTS tasks (
              id         VARCHAR(36)  NOT NULL PRIMARY KEY,
              version    BIGINT       NOT NULL DEFAULT 0,
              project_id VARCHAR(100) NOT NULL,
              status     VARCHAR(20)  NOT NULL,
              created_at TIMESTAMP(3) NOT NULL,
              updated_at TIMESTAMP(3) NOT NULL,
              snapshot   LONGTEXT     NOT NULL,
              KEY idx_tasks_created (created_at)
            ) CHARACTER SET utf8mb4""",
        )
        jdbc.execute(
            """
            CREATE TABLE IF NOT EXISTS task_events (
              id      BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
              task_id VARCHAR(36)  NOT NULL,
              at      TIMESTAMP(3) NOT NULL,
              stage   VARCHAR(30),
              type    VARCHAR(30),
              data    MEDIUMTEXT,
              KEY idx_events_task (task_id, id)
            ) CHARACTER SET utf8mb4""",
        )
        jdbc.execute(
            """
            CREATE TABLE IF NOT EXISTS projects (
              id        VARCHAR(100) NOT NULL PRIMARY KEY,
              name      VARCHAR(200) NOT NULL,
              runner_id VARCHAR(100),
              last_seen TIMESTAMP(3) NOT NULL
            ) CHARACTER SET utf8mb4""",
        )
        ready.set(true)
    }
}
