package com.example.coderiskanalyzer.production.cli;

import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.boot.ExitCodeGenerator;
import org.springframework.stereotype.Component;

@Component
public class CliExitCode implements ExitCodeGenerator {

    private final AtomicInteger exitCode = new AtomicInteger();

    public void set(int code) {
        exitCode.set(code);
    }

    @Override
    public int getExitCode() {
        return exitCode.get();
    }
}
