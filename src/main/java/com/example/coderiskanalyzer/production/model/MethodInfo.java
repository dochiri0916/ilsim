package com.example.coderiskanalyzer.production.model;

import java.util.List;
import java.util.Set;

public record MethodInfo(
        String ownerClass,
        String name,
        String returnType,
        List<String> parameterTypes,
        Set<String> annotations,
        Set<String> modifiers,
        List<String> methodCalls,
        SourceLocation location,
        List<ParameterInfo> parameters,
        List<String> throwsTypes,
        List<String> fieldAccesses,
        String body,
        List<MethodCallInfo> callDetails) {

    public MethodInfo(String ownerClass, String name, String returnType, List<String> parameterTypes,
                      Set<String> annotations, Set<String> modifiers, List<String> methodCalls,
                      SourceLocation location) {
        this(ownerClass, name, returnType, parameterTypes, annotations, modifiers, methodCalls, location,
                List.of(), List.of(), List.of(), "", List.of());
    }

    public MethodInfo {
        parameterTypes = List.copyOf(parameterTypes);
        annotations = Set.copyOf(annotations);
        modifiers = Set.copyOf(modifiers);
        methodCalls = List.copyOf(methodCalls);
        parameters = List.copyOf(parameters);
        throwsTypes = List.copyOf(throwsTypes);
        fieldAccesses = List.copyOf(fieldAccesses);
        body = body == null ? "" : body;
        callDetails = List.copyOf(callDetails);
    }

    public boolean isHttpEntryPoint() {
        return hasAnnotation("RequestMapping", "GetMapping", "PostMapping", "PutMapping", "PatchMapping", "DeleteMapping");
    }

    public boolean isMessageListener() {
        return hasAnnotation("KafkaListener", "RabbitListener", "JmsListener");
    }

    public boolean isScheduledJob() { return hasAnnotation("Scheduled"); }
    public boolean isAsync() { return hasAnnotation("Async"); }
    public boolean isTransactional() { return hasAnnotation("Transactional"); }

    private boolean hasAnnotation(String... names) {
        return annotations.stream().map(annotation -> {
            int dot = annotation.lastIndexOf('.');
            return dot < 0 ? annotation : annotation.substring(dot + 1);
        }).anyMatch(name -> java.util.Arrays.asList(names).contains(name));
    }
}
