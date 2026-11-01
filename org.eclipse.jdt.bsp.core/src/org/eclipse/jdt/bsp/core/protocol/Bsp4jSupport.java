package org.eclipse.jdt.bsp.core.protocol;

import java.net.URI;
import java.util.List;
import ch.epfl.scala.bsp4j.BuildClientCapabilities;
import ch.epfl.scala.bsp4j.InitializeBuildParams;

public final class Bsp4jSupport {

	private Bsp4jSupport() {
		// Utility class
	}

	public static InitializeBuildParams createInitializeParams(URI rootUri, String displayName, String version) {
		InitializeBuildParams params = new InitializeBuildParams(
				displayName,
				version,
				"2.2.0-M4",
				rootUri.toString(),
				new BuildClientCapabilities(List.of("java")));
		return params;
	}
}
