package it.filippor.jdt.bsp.core.model;

import java.nio.file.Path;
import java.util.List;

import org.eclipse.jdt.core.IClasspathEntry;

public record BspBuildTargetModel(String buildTargetId,
                                  List<Path> sources,
                                  List<IClasspathEntry> classpath,
                                  List<String> targetDependencies,
                                  Path outputFolder,
                                  String javaSourceLevel) {
}
