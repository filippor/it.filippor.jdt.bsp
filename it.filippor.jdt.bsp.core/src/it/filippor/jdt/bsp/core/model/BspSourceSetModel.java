package it.filippor.jdt.bsp.core.model;

import java.nio.file.Path;
import java.util.List;

import org.eclipse.jdt.core.IClasspathEntry;

public record BspSourceSetModel(String sourceSetId,
                                List<Path> sources,
                                List<IClasspathEntry> compileClasspath,
                                List<IClasspathEntry> runtimeClasspath,
                                List<IClasspathEntry> annotationProcessorClasspath,
                                List<String> targetDependencies,
                                Path outputFolder,
                                String javaSourceLevel,
                                String javaEncoding) {
}