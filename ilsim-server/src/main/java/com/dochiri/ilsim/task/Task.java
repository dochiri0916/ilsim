package com.dochiri.ilsim.task;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

public class Task {

    public enum Status { ACTIVE, COMPLETED, BLOCKED }

    public record Event(Instant at, String stage, String type, String data) {}

    public final String id;
    public final String projectId;
    public final String feature;
    public final String requirement;
    public final Instant createdAt;
    public final List<StageState> stages =
            Arrays.stream(Stage.values()).map(StageState::new).toList();
    public volatile Status status = Status.ACTIVE;
    public volatile String runnerId;
    public volatile String branch;
    /** 사람이 개입(거절·재시도)한 뒤로 Runner에 배정된 횟수. 자동 재시도가 끝없이 이어지지 않게 하는 전체 상한에 쓴다. */
    public volatile int runs;

    public Task(String projectId, String feature, String requirement) {
        this(UUID.randomUUID().toString(), Instant.now(), projectId, feature, requirement);
    }

    /** 저장소에서 되살릴 때 쓴다. */
    Task(String id, Instant createdAt, String projectId, String feature, String requirement) {
        this.id = id;
        this.createdAt = createdAt;
        this.projectId = projectId;
        this.requirement = requirement;
        this.feature = feature == null || feature.isBlank() ? "feat-" + id.substring(0, 8) : feature;
    }

    /** 아직 통과하지 못한 첫 단계. 모두 통과했으면 null. */
    public StageState current() {
        return stages.stream().filter(s -> s.status != StageState.Status.PASSED).findFirst().orElse(null);
    }
}
