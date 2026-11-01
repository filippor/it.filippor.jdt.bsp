package org.eclipse.jdt.bsp.core.model;

import java.nio.file.Path;
import java.util.List;

public record BspSourceSetModel(String sourceSetId, List<Path> sources, List<Path> classpath,
		List<String> targetDependencies, Path outputFolder, String javaSourceLevel) {
}
