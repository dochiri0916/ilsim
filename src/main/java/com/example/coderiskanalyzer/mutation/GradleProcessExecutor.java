package com.example.coderiskanalyzer.mutation;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

/** Small process boundary so timeout and exit handling can be tested without running a target build. */
public interface GradleProcessExecutor {
    Result execute(List<String> command, Path workingDirectory, Duration timeout, Path stdout, Path stderr)
            throws IOException, InterruptedException;

    record Result(int exitCode, boolean timedOut) {}
}
