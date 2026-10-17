package com.dochiri.ilsim.task;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 작업 한 건의 저장용 사본. 저장소는 이 사본만 알고 도메인 객체(Task, StageState)는 모른다.
 * 되살릴 때 실행 중이던 단계는 Runner 가 사라졌으므로 PENDING 으로 되돌린다.
 */
record TaskSnapshot(
        String id, String projectId, String feature, String requirement, Instant createdAt,
        String status, String runnerId, String branch, int runs, List<StageSnapshot> stages) {

    record StageSnapshot(
            String stage, String status, int attempt, String feedback, String lastErrorKey, String error, String diff,
            String artifactHash, Map<String, String> artifacts, Map<String, String> validation,
            Map<String, String> routing, List<String> routingLog) {}

    static TaskSnapshot of(Task t) {
        List<StageSnapshot> stages = t.stages.stream().map(s -> new StageSnapshot(
                s.stage.name(), s.status.name(), s.attempt, s.feedback, s.lastErrorKey, s.error, s.diff, s.artifactHash,
                new LinkedHashMap<>(s.artifacts), new LinkedHashMap<>(s.validation),
                new LinkedHashMap<>(s.routing), new ArrayList<>(s.routingLog))).toList();
        return new TaskSnapshot(t.id, t.projectId, t.feature, t.requirement, t.createdAt, t.status.name(), t.runnerId, t.branch, t.runs, stages);
    }

    Task restore() {
        Task t = new Task(id, createdAt, projectId, feature, requirement);
        t.status = Task.Status.valueOf(status);
        t.runnerId = runnerId;
        t.branch = branch;
        t.runs = runs;
        for (StageSnapshot ss : stages) {
            StageState s = t.stages.stream().filter(x -> x.stage.name().equals(ss.stage())).findFirst().orElse(null);
            if (s == null) continue;
            StageState.Status restored = StageState.Status.valueOf(ss.status());
            s.status = restored == StageState.Status.RUNNING ? StageState.Status.PENDING : restored;
            s.attempt = ss.attempt();
            s.feedback = ss.feedback();
            s.lastErrorKey = ss.lastErrorKey();
            s.error = ss.error();
            s.diff = ss.diff();
            s.artifactHash = ss.artifactHash();
            if (ss.artifacts() != null) s.artifacts = new LinkedHashMap<>(ss.artifacts());
            if (ss.validation() != null) s.validation = new LinkedHashMap<>(ss.validation());
            if (ss.routing() != null) s.routing = new LinkedHashMap<>(ss.routing());
            if (ss.routingLog() != null) s.routingLog.addAll(ss.routingLog());
        }
        return t;
    }
}
