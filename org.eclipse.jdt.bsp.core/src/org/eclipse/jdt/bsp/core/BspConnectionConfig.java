package org.eclipse.jdt.bsp.core;

import java.nio.file.Path;
import java.util.List;

public record BspConnectionConfig(List<String> command, Path workingDirectory) {
}
