package it.filippor.jdt.bsp.core.model;

public record BspBuildTargetActionModel(
                                        String buildTargetId,
                                        String displayName,
                                        boolean canPackage,
                                        boolean canRun,
                                        boolean canTest) {
}
