package com.example.coderiskanalyzer.production.analysis;

import java.util.List;

import com.example.coderiskanalyzer.production.analysis.callgraph.CallGraph;
import com.example.coderiskanalyzer.production.analysis.callgraph.CallGraphBuilder;
import com.example.coderiskanalyzer.production.model.ProjectModel;
import com.example.coderiskanalyzer.production.parser.ParsedSource;

public record AnalysisContext(ProjectModel projectModel, List<ParsedSource> parsedSources, CallGraph callGraph) {
    public AnalysisContext(ProjectModel projectModel, List<ParsedSource> parsedSources) {
        this(projectModel, parsedSources, new CallGraphBuilder().build(parsedSources));
    }

    public AnalysisContext {
        parsedSources = List.copyOf(parsedSources);
    }
}
