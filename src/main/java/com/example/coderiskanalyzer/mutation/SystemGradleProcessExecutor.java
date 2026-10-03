package com.example.coderiskanalyzer.mutation;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;

@Component
public class SystemGradleProcessExecutor implements GradleProcessExecutor {
    @Override
    public Result execute(List<String> command, Path workingDirectory, Duration timeout, Path stdout, Path stderr)
            throws IOException, InterruptedException {
        Process process = new ProcessBuilder(command).directory(workingDirectory.toFile())
                .redirectOutput(stdout.toFile()).redirectError(stderr.toFile()).start();
        boolean completed;
        try {
            completed = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException exception) {
            stop(process);
            throw exception;
        }
        if (!completed) {
            stop(process);
            return new Result(-1, true);
        }
        return new Result(process.exitValue(), false);
    }

    private void stop(Process process) {
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
        try { process.waitFor(5, TimeUnit.SECONDS); }
        catch (InterruptedException exception) { Thread.currentThread().interrupt(); }
    }
}
