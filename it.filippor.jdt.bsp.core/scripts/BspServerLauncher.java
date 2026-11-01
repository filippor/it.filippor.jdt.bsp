///usr/bin/env jbang "$0" "$@" ; exit $?
//DEPS ch.epfl.scala:bsp4j:2.2.0-M4.TEST
//DEPS org.eclipse.lsp4j:org.eclipse.lsp4j.jsonrpc:0.24.0

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.eclipse.lsp4j.jsonrpc.Launcher;

import ch.epfl.scala.bsp4j.BuildClient;
import ch.epfl.scala.bsp4j.BuildClientCapabilities;
import ch.epfl.scala.bsp4j.BuildServer;
import ch.epfl.scala.bsp4j.DidChangeBuildTarget;
import ch.epfl.scala.bsp4j.InitializeBuildParams;
import ch.epfl.scala.bsp4j.LogMessageParams;
import ch.epfl.scala.bsp4j.PublishDiagnosticsParams;
import ch.epfl.scala.bsp4j.PrintParams;
import ch.epfl.scala.bsp4j.ShowMessageParams;
import ch.epfl.scala.bsp4j.TaskFinishParams;
import ch.epfl.scala.bsp4j.TaskProgressParams;
import ch.epfl.scala.bsp4j.TaskStartParams;
import ch.epfl.scala.bsp4j.WorkspaceBuildTargetsResult;

public class BspServerLauncher {
    //-Dplugin.dir=C:\localGit\my\build-server-for-gradle\server\build\libs\plugins -cp
    private static final String DEFAULT_WORK_DIR =
            "C:\\localGit\\my\\build-server-for-gradle";

    private static final String PLUGIN_DIR =
            "C:\\localGit\\my\\build-server-for-gradle\\server\\build\\libs\\plugins";

    private static final String SERVER_JAR =
            "C:\\localGit\\my\\build-server-for-gradle\\server\\build\\libs\\server.jar";

    private static final String RUNTIME_GLOB =
            "C:\\localGit\\my\\build-server-for-gradle\\server\\build\\libs\\runtime\\*";

    public static void main(String[] args) throws Exception {
        boolean dryRun = false;
        boolean handshake = false;
        String workingDir = DEFAULT_WORK_DIR;

        for (String arg : args) {
            if ("--dry-run".equals(arg)) {
                dryRun = true;
            } else if ("--handshake".equals(arg)) {
                handshake = true;
            } else {
                workingDir = arg;
            }
        }

        List<String> command = buildCommand();

        String hostWorkingDir = toHostPath(workingDir);
        System.out.println("Working directory: " + hostWorkingDir);
        System.out.println("Command: " + String.join(" ", command));

        if (dryRun) {
            return;
        }

        if (handshake) {
            runHandshake(hostWorkingDir);
            return;
        }

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(Path.of(hostWorkingDir).toFile());
        pb.redirectInput(ProcessBuilder.Redirect.INHERIT);
        pb.redirectErrorStream(true);

        Process process = pb.start();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            if (process.isAlive()) {
                process.destroy();
            }
        }));

        System.out.println("BSP server started. PID=" + process.pid());
        pipeOutput(process);

        int exitCode = process.waitFor();
        System.out.println("BSP server exited with code " + exitCode);
    }

    private static void runHandshake(String hostWorkingDir) throws Exception {
        List<String> command = buildCommand();
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(Path.of(hostWorkingDir).toFile());
        // Keep stderr separate so non-BSP logs don't corrupt JSON-RPC framing.
        pb.redirectErrorStream(false);

        Process process = pb.start();
        pipeStream(process.getErrorStream(), "[bsp-err] ", "bsp-error");
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            if (process.isAlive()) {
                process.destroy();
            }
        }));

        ExecutorService executor = Executors.newCachedThreadPool();
        try {
            BuildClient client = new LoggingBuildClient();
            Launcher<BuildServer> launcher = new Launcher.Builder<BuildServer>()
                    .setInput(process.getInputStream())
                    .setOutput(process.getOutputStream())
                    .setLocalService(client)
                    .setRemoteInterface(BuildServer.class)
                    .setExecutorService(executor)
                    .create();

            BuildServer server = launcher.getRemoteProxy();
            launcher.startListening();

            InitializeBuildParams init = new InitializeBuildParams(
                    "jbang-bsp-client",
                    "0.1.0",
                    "2.2.0-M4",
                    Path.of(hostWorkingDir).toUri().toString(),
                    new BuildClientCapabilities(List.of("java")));

            System.out.println("[handshake] buildInitialize...");
            System.out.println("[handshake] initResult=" + server.buildInitialize(init).get(30, TimeUnit.SECONDS));

            System.out.println("[handshake] buildInitialized...");
            server.onBuildInitialized();

            System.out.println("[handshake] workspaceBuildTargets...");
            WorkspaceBuildTargetsResult targets = server.workspaceBuildTargets().get(30, TimeUnit.SECONDS);
            int count = targets == null || targets.getTargets() == null ? 0 : targets.getTargets().size();
            System.out.println("[handshake] targetCount=" + count);
            if (targets != null && targets.getTargets() != null) {
                targets.getTargets().forEach(t -> System.out.println("[handshake] target=" + t.getDisplayName()));
            }

            System.out.println("[handshake] shutdown...");
            server.buildShutdown().get(10, TimeUnit.SECONDS);
            server.onBuildExit();
        } finally {
            executor.shutdownNow();
            if (process.isAlive()) {
                process.destroy();
            }
        }
    }

    private static List<String> buildCommand() {
        boolean windows = isWindowsHost();
        String pluginDir = toHostPath(PLUGIN_DIR);
        String classpath = toHostClasspath(SERVER_JAR, RUNTIME_GLOB);

        List<String> command = new ArrayList<>();
        if (windows) {
            command.add("cmd");
            command.add("/c");
        }
        command.add("java");
        command.add("-Dplugin.dir=" + pluginDir);
        command.add("-cp");
        command.add(classpath);
        command.add("com.microsoft.java.bs.core.Launcher");
        return command;
    }

    private static String toHostClasspath(String... entries) {
        String sep = isWindowsHost() ? ";" : ":";
        List<String> converted = new ArrayList<>(entries.length);
        for (String entry : entries) {
            converted.add(toHostPath(entry));
        }
        return String.join(sep, converted);
    }

    private static boolean isWindowsHost() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    private static String toHostPath(String path) {
        if (path == null || path.isBlank()) {
            return path;
        }
        if (isWindowsHost()) {
            return path;
        }
        // Convert C:\foo\bar to /mnt/c/foo/bar for WSL/Linux hosts.
        if (path.length() >= 3 && Character.isLetter(path.charAt(0)) && path.charAt(1) == ':'
                && (path.charAt(2) == '\\' || path.charAt(2) == '/')) {
            char drive = Character.toLowerCase(path.charAt(0));
            String tail = path.substring(2).replace('\\', '/');
            return "/mnt/" + drive + tail;
        }
        return path.replace('\\', '/');
    }

    private static void pipeOutput(Process process) throws IOException {
        pipeStream(process.getInputStream(), "[bsp] ", "bsp-output");
    }

    private static void pipeStream(java.io.InputStream stream, String prefix, String threadName) {
        Thread thread = new Thread(() -> {
            try (BufferedReader br = new BufferedReader(new InputStreamReader(stream))) {
                String line;
                while ((line = br.readLine()) != null) {
                    System.out.println(prefix + line);
                }
            } catch (IOException e) {
                System.out.println(prefix + "stream closed: " + e.getMessage());
            }
        }, threadName);
        thread.setDaemon(true);
        thread.start();
    }

    private static final class LoggingBuildClient implements BuildClient {
        @Override
        public void onBuildShowMessage(ShowMessageParams params) {
            System.out.println("[server] showMessage: " + params.getMessage());
        }

        @Override
        public void onBuildLogMessage(LogMessageParams params) {
            System.out.println("[server] logMessage: " + params.getMessage());
        }

        @Override
        public void onBuildPublishDiagnostics(PublishDiagnosticsParams params) {
            // Keep output concise for smoke testing.
        }

        @Override
        public void onBuildTargetDidChange(DidChangeBuildTarget params) {
            // No-op for smoke testing.
        }

        @Override
        public void onBuildTaskStart(TaskStartParams params) {
            // No-op for smoke testing.
        }

        @Override
        public void onBuildTaskProgress(TaskProgressParams params) {
            // No-op for smoke testing.
        }

        @Override
        public void onBuildTaskFinish(TaskFinishParams params) {
            // No-op for smoke testing.
        }

        @Override
        public void onRunPrintStdout(PrintParams params) {
            // Keep output concise for smoke testing.
        }

        @Override
        public void onRunPrintStderr(PrintParams params) {
            // Keep output concise for smoke testing.
        }
    }
}
