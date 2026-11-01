package org.eclipse.jdt.bsp.core;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

public final class BspMessageIO {

	private static final byte[] HEADER_SEPARATOR = "\r\n\r\n".getBytes(StandardCharsets.US_ASCII);
	private static final String CONTENT_LENGTH = "Content-Length:";

	private BspMessageIO() {
		// Utility class
	}

	public static void writeMessage(OutputStream outputStream, String payload) throws IOException {
		byte[] body = payload.getBytes(StandardCharsets.UTF_8);
		String header = CONTENT_LENGTH + " " + body.length + "\r\n\r\n";
		outputStream.write(header.getBytes(StandardCharsets.US_ASCII));
		outputStream.write(body);
		outputStream.flush();
	}

	public static String readMessage(InputStream inputStream) throws IOException {
		String headerBlock = readHeaderBlock(inputStream);
		if (headerBlock == null) {
			return null;
		}
		int contentLength = parseContentLength(headerBlock);
		byte[] body = inputStream.readNBytes(contentLength);
		if (body.length < contentLength) {
			return null;
		}
		return new String(body, StandardCharsets.UTF_8);
	}

	private static String readHeaderBlock(InputStream inputStream) throws IOException {
		ByteArrayOutputStream headerBytes = new ByteArrayOutputStream();
		int matchIndex = 0;
		while (true) {
			int value = inputStream.read();
			if (value < 0) {
				if (headerBytes.size() == 0) {
					return null;
				}
				throw new IOException("Unexpected EOF while reading BSP headers");
			}
			headerBytes.write(value);
			if (value == HEADER_SEPARATOR[matchIndex]) {
				matchIndex++;
				if (matchIndex == HEADER_SEPARATOR.length) {
					break;
				}
			} else {
				matchIndex = (value == HEADER_SEPARATOR[0]) ? 1 : 0;
			}
		}
		byte[] bytes = headerBytes.toByteArray();
		return new String(bytes, 0, bytes.length - HEADER_SEPARATOR.length, StandardCharsets.US_ASCII);
	}

	private static int parseContentLength(String headerBlock) throws IOException {
		for (String line : headerBlock.split("\\r\\n")) {
			if (line.regionMatches(true, 0, CONTENT_LENGTH, 0, CONTENT_LENGTH.length())) {
				String lengthValue = line.substring(CONTENT_LENGTH.length()).trim();
				try {
					return Integer.parseInt(lengthValue);
				} catch (NumberFormatException e) {
					throw new IOException("Invalid Content-Length header: " + lengthValue, e);
				}
			}
		}
		throw new IOException("Missing Content-Length header in BSP message");
	}
}
