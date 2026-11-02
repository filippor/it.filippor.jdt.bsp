package it.filippor.jdt.bsp.core;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

public record BspConnectionConfig(List<String> command,
                                  Path workingDirectory,
                                  Map<String, Object> initializeData) {

    public BspConnectionConfig(List<String> command, Path workingDirectory) {
        this(command, workingDirectory, Map.of());
    }
}
