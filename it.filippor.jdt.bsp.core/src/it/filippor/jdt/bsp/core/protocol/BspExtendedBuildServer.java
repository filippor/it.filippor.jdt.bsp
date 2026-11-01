package it.filippor.jdt.bsp.core.protocol;

import java.util.concurrent.CompletableFuture;
import org.eclipse.lsp4j.jsonrpc.services.JsonRequest;
import ch.epfl.scala.bsp4j.BuildServer;

public interface BspExtendedBuildServer extends BuildServer {

	@JsonRequest("buildTarget/javacOptions")
	CompletableFuture<Object> buildTargetJavacOptions(BspTargetParams params);

	@JsonRequest("buildTarget/jvmCompileClasspath")
	CompletableFuture<Object> buildTargetJvmCompileClasspath(BspTargetParams params);

	@JsonRequest("buildTarget/compileClasspath")
	CompletableFuture<Object> buildTargetCompileClasspath(BspTargetParams params);
}
