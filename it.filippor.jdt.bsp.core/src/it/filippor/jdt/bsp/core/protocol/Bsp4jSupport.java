package it.filippor.jdt.bsp.core.protocol;

import java.net.URI;
import java.util.List;
import java.util.Map;

import ch.epfl.scala.bsp4j.BuildClientCapabilities;
import ch.epfl.scala.bsp4j.InitializeBuildParams;

public final class Bsp4jSupport {

    private Bsp4jSupport() {
        // Utility class
    }

    public static InitializeBuildParams createInitializeParams(URI rootUri, String displayName, String version) {
        return createInitializeParams(rootUri, displayName, version, Map.of());
    }

    public static InitializeBuildParams createInitializeParams(URI rootUri, String displayName, String version,
                                                               Map<String, Object> data) {
        InitializeBuildParams params = new InitializeBuildParams(
            displayName,
            version,
            "2.2.0-M4",
            rootUri.toString(),
            new BuildClientCapabilities(List.of("java")));
        if (data != null && !data.isEmpty()) {
            params.setData(data);
        }
        return params;
    }
}
