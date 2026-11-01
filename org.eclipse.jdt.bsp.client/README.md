# org.eclipse.jdt.bsp.client

UI plug-in for controlling the BSP client in Eclipse IDE.

## What this provides

- Start/stop commands in Eclipse (`JDT BSP: Start Client`, `JDT BSP: Stop Client`).
- Preference-driven BSP command line setup.
- Integration point to the headless implementation in `org.eclipse.jdt.bsp.core`.
- Strict BSP-first mode: no automatic workspace project/classpath synchronization.

## Module split

- `org.eclipse.jdt.bsp.client` contains Eclipse UI commands/handlers and preferences.
- `org.eclipse.jdt.bsp.core` contains transport, model mapping and BSP4J dependency.

## Preferences

Preference key:

- `org.eclipse.jdt.bsp.client.commandLine`
- `org.eclipse.jdt.bsp.client.rootDirectories`

Default value:

- `gradle bsp`
- empty (fallback to current Eclipse workspace root)

This is split by whitespace. For example, use `mill mill.bsp.BSP/install` or any BSP server command line available on your machine. On Windows, if using a wrapper per project root, `cmd /c gradlew.bat bsp` is often the easiest setup.

For multiple Gradle root projects in the same Eclipse workspace, set
`org.eclipse.jdt.bsp.client.rootDirectories` to a semicolon-separated list of absolute directories
(newline-separated also supported). The client starts one BSP session per configured root.

## Try it in Eclipse

1. Import the `org.eclipse.jdt.bsp.client` bundle into your target/workspace.
2. Launch Eclipse with this bundle in the target platform.
3. Use `File -> Import -> General -> Gradle Project (BSP)` to configure command line and root directories.
4. Run command palette action: `JDT BSP: Start Client` (or select start in the wizard).
5. Run command palette action: `JDT BSP: Stop Client` to terminate.

Wizard command modes:

- `Gradle wrapper`: `cmd /c gradlew.bat bsp` (Windows-friendly per-root setup)
- `System Gradle`: `gradle bsp`
- `Specific executable path`: choose a Gradle binary and the wizard builds `<path> bsp`
- `Custom command`: fully custom BSP server startup command

## Core self-test

Run the framing test from the core module:

```powershell
javac -d C:\temp\bsp-test C:\Users\10144242\eclipses\jdt-master\git\eclipse.jdt.core\org.eclipse.jdt.bsp.core\src\org\eclipse\jdt\bsp\core\BspMessageIO.java C:\Users\10144242\eclipses\jdt-master\git\eclipse.jdt.core\org.eclipse.jdt.bsp.core\src\org\eclipse\jdt\bsp\core\demo\BspFramingSelfTest.java
java -cp C:\temp\bsp-test org.eclipse.jdt.bsp.core.demo.BspFramingSelfTest
```

## Current scope and next steps

Current UI implementation focuses on Eclipse command integration.

Natural next steps:

- Move BSP start/initialize handshake orchestration into `org.eclipse.jdt.bsp.core` APIs.
- Add `jdtls` adapter layer that calls the same core APIs.
- Add tests in an Eclipse test bundle for source-set project mapping flows.
