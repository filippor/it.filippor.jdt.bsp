package it.filippor.jdt.bsp.ui.ui.view;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.console.ConsolePlugin;
import org.eclipse.ui.console.IConsole;
import org.eclipse.ui.console.IConsoleManager;
import org.eclipse.ui.console.MessageConsole;
import org.eclipse.ui.console.MessageConsoleStream;

public final class BspConsole {

	private static final Map<String, MessageConsole> CONSOLES = new ConcurrentHashMap<>();

	private BspConsole() {
		// utility
	}

	public static void write(String root, String message, boolean error) {
		if (message == null) {
			return;
		}
		Display display = Display.getDefault();
		if (display == null || display.isDisposed()) {
			return;
		}
		display.asyncExec(() -> {
			if (display.isDisposed()) {
				return;
			}
			MessageConsole console = getOrCreateConsole(root);
			ConsolePlugin.getDefault().getConsoleManager().showConsoleView(console);
			MessageConsoleStream stream = console.newMessageStream();
			stream.println(error ? "[ERROR] " + message : message);
			try {
				stream.close();
			} catch (Exception e) {
				// best effort
			}
		});
	}

	private static MessageConsole getOrCreateConsole(String root) {
		String label = root == null || root.isBlank() ? "BSP" : root;
		return CONSOLES.computeIfAbsent(label, key -> {
			MessageConsole console = new MessageConsole("BSP - " + key, null);
			IConsoleManager manager = ConsolePlugin.getDefault().getConsoleManager();
			manager.addConsoles(new IConsole[] { console });
			return console;
		});
	}
}
