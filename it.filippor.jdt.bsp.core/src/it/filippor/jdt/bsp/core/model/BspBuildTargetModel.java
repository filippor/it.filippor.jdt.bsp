package it.filippor.jdt.bsp.core.model;

import java.nio.file.Path;
import java.util.List;

public record BspBuildTargetModel(String buildTargetId, List<Path> sources, List<Path> classpath,
		List<String> targetDependencies, Path outputFolder, String javaSourceLevel) {
}
