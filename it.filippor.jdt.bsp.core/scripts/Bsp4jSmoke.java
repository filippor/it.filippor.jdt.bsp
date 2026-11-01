///usr/bin/env jbang "$0" "$@" ; exit $?
//DEPS ch.epfl.scala:bsp4j:2.2.0-M4.TEST

import java.nio.file.Path;
import java.util.List;

import ch.epfl.scala.bsp4j.BuildClientCapabilities;
import ch.epfl.scala.bsp4j.InitializeBuildParams;

public class Bsp4jSmoke {

    public static void main(String[] args) {
        String rootUri = args.length > 0
                ? Path.of(args[0]).toUri().toString()
                : Path.of(".").toAbsolutePath().normalize().toUri().toString();

        InitializeBuildParams params = new InitializeBuildParams(
                "jbang-bsp-client",
                "0.1.0",
                "2.2.0-M4",
                rootUri,
                new BuildClientCapabilities(List.of("java")));

        System.out.println("BSP4J loaded: " + InitializeBuildParams.class.getName());
        System.out.println("displayName=" + params.getDisplayName());
        System.out.println("version=" + params.getVersion());
        System.out.println("bspVersion=" + params.getBspVersion());
        System.out.println("rootUri=" + params.getRootUri());
        System.out.println("languageIds=" + params.getCapabilities().getLanguageIds());
    }
}
