package org.eclipse.jdt.bsp.core.demo;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import org.eclipse.jdt.bsp.core.BspMessageIO;

public class BspFramingSelfTest {

	public static void main(String[] args) throws Exception {
		String message = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"build/initialize\"}";
		ByteArrayOutputStream output = new ByteArrayOutputStream();
		BspMessageIO.writeMessage(output, message);
		ByteArrayInputStream input = new ByteArrayInputStream(output.toByteArray());
		String decoded = BspMessageIO.readMessage(input);
		if (!message.equals(decoded)) {
			throw new IllegalStateException("Decoded message differs from encoded payload");
		}
		System.out.println("BSP framing self-test passed");
		System.out.println(new String(output.toByteArray(), StandardCharsets.UTF_8));
	}
}
