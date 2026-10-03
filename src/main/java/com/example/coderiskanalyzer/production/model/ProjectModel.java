package com.example.coderiskanalyzer.production.model;

import java.util.List;

public record ProjectModel(List<ClassInfo> classes) {
    public ProjectModel {
        classes = List.copyOf(classes);
    }
}
