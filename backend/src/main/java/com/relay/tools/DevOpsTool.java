package com.relay.tools;

public interface DevOpsTool {
    String readFile(String path);

    String writeFile(String path, String content);

    String runBuild(String projectPath);

    String createPR(String branch, String title);
}