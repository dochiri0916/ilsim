package com.example.coderiskanalyzer.production.analysis.dataflow;

import com.example.coderiskanalyzer.production.model.SourceLocation;

public record DataFlowPath(TaintSource source, String sinkMethod, SourceLocation sinkLocation) {
}
