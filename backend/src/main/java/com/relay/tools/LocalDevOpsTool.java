package com.relay.tools;

import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

@Component
@Primary
public class LocalDevOpsTool implements DevOpsTool {

    @Override
    public String readFile(String path) {
        try {
            return Files.readString(Path.of(path));
        } catch (IOException e) {
            return "ERROR: " + e.getMessage();
        }
    }

    @Override
    public String writeFile(String path, String content) {
        try {
            Path p = Path.of(path);
            if (p.getParent() != null) {
                Files.createDirectories(p.getParent());
            }
            Files.writeString(p, content);
            return "OK: wrote " + path;
        } catch (IOException e) {
            return "ERROR: " + e.getMessage();
        }
    }

    @Override
    public String runBuild(String projectPath) {
        try {
            boolean isMaven = Files.exists(Path.of(projectPath, "pom.xml"));
            ProcessBuilder pb;
            if (isMaven) {
                pb = new ProcessBuilder("mvn", "clean", "compile");
            } else {
                pb = new ProcessBuilder("npm", "run", "build");
            }
            pb.directory(new File(projectPath));
            pb.redirectErrorStream(true);
            Process p = pb.start();
            String output = new String(p.getInputStream().readAllBytes());
            int exit = p.waitFor();
            if (exit == 0)
                return "PASS";
            return "FAIL:\n" + sliceError(output, 15);
        } catch (Exception e) {
            return "FAIL: " + e.getMessage();
        }
    }

    @Override
    public String createPR(String branch, String title) {
        return "https://github.com/mock/pr/local-" + System.currentTimeMillis();
    }

    private String sliceError(String log, int lines) {
        String[] arr = log.split("\n");
        StringBuilder sb = new StringBuilder();
        int count = 0;
        for (String line : arr) {
            if (line.contains("ERROR") || line.contains("error")) {
                sb.append(line).append("\n");
                if (++count >= lines)
                    break;
            }
        }
        if (sb.length() == 0) {
            return log.substring(0, Math.min(500, log.length()));
        }
        return sb.toString();
    }
}