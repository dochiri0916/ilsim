package com.example.staticrules;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class FileController {
    private Path root;

    // EXPECT: no SEC-003 (normalized and contained)
    public String safe(@RequestParam String path) throws IOException {
        Path base = root.toRealPath();
        Path resolved = base.resolve(path).normalize();
        if (!resolved.startsWith(base)) throw new IllegalArgumentException();
        return Files.readString(resolved);
    }

    // EXPECT: SEC-003 (startsWith without normalize() does not stop "../")
    public String unnormalized(@RequestParam String path) throws IOException {
        Path base = root.toRealPath();
        Path resolved = base.resolve(path);
        if (!resolved.startsWith(base)) throw new IllegalArgumentException();
        return Files.readString(resolved);
    }
}
