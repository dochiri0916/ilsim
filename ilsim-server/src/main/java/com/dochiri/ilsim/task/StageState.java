package com.dochiri.ilsim.task;

import java.util.LinkedHashMap;
import java.util.Map;

public class StageState {

    public enum Status { PENDING, RUNNING, WAITING_APPROVAL, PASSED, FAILED }

    public final Stage stage;
    public volatile Status status = Status.PENDING;
    public volatile int attempt = 1;
    public volatile String feedback;
    /** 직전 실패의 정규화한 사유. 같은 사유가 연달아 나오면 더 시도해도 달라지지 않는다고 본다. */
    public volatile String lastErrorKey;
    /** JEV 평가 결과(complexity, risk)와 이번에 고른 모델. 같은 단계의 재시도에서는 평가를 다시 하지 않고 재사용한다. */
    public volatile Map<String, String> routing = new LinkedHashMap<>();
    /** 모델 선택 이력: 시도마다 어떤 모델을 왜 골랐는지. */
    public final java.util.List<String> routingLog = new java.util.concurrent.CopyOnWriteArrayList<>();
    public volatile String error;
    public volatile String diff;
    public volatile String artifactHash;
    public volatile Map<String, String> artifacts = new LinkedHashMap<>();
    /** tests/analysis 등 검증 항목별 결과. 실행하지 못한 항목은 UNKNOWN. */
    public volatile Map<String, String> validation = new LinkedHashMap<>();

    public StageState(Stage stage) {
        this.stage = stage;
    }
}
