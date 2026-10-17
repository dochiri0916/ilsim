package com.dochiri.ilsim.task;

import org.springframework.stereotype.Component;

@Component
class RuleModelRouter implements ModelRouter {

    @Override
    public String route(Task task, Stage stage, int attempt) {
        if (attempt >= 3) return "opus";
        return "sonnet";
    }
}
