package it.filippor.jdt.bsp.core;

public record BspActionableTaskItem(
        String root,
        String buildTarget,
        String buildTargetId,
        String task,
        boolean exposed) {
}
