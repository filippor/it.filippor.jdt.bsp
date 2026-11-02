package it.filippor.jdt.bsp.ui;

public record BspActionableTaskItem(
		String root,
		String buildTarget,
		String buildTargetId,
		String task,
		boolean exposed) {
}
