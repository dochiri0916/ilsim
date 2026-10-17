package com.dochiri.ilsim.task;

/** 단계·시도 횟수에 따라 Claude 모델을 고른다. 나중에 JEV 평가 기반 구현으로 교체한다. */
public interface ModelRouter {

    String route(Task task, Stage stage, int attempt);
}
