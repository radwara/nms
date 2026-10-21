package com.example.nms.support;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Sends a request byte-for-byte over a socket, so paths with dot segments and broken framing reach
 * the server exactly as written (HTTP clients normalize or refuse them).
 */
public final class RawHttp {

  /** Status, lower-cased headers, and body of a response. */
  public record Response(int status, Map<String, String> headers, String body) {

    public String header(String name) {
      return headers.get(name.toLowerCase(Locale.ROOT));
    }
  }

  private RawHttp() {}

  /** {@code head} is the request line plus headers, without the blank line. */
  public static Response send(int port, String head, String body) throws IOException {
    try (Socket socket = new Socket("localhost", port)) {
      socket.setSoTimeout(10_000);
      OutputStream out = socket.getOutputStream();
      out.write((head + "\r\nConnection: close\r\n\r\n" + body).getBytes(StandardCharsets.UTF_8));
      out.flush();
      return parse(readAll(socket.getInputStream()));
    }
  }

  private static String readAll(InputStream in) throws IOException {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    in.transferTo(bytes);
    return bytes.toString(StandardCharsets.UTF_8);
  }

  private static Response parse(String raw) {
    int split = raw.indexOf("\r\n\r\n");
    String[] lines = raw.substring(0, split).split("\r\n");
    Map<String, String> headers = new LinkedHashMap<>();
    for (int i = 1; i < lines.length; i++) {
      int colon = lines[i].indexOf(':');
      headers.put(
          lines[i].substring(0, colon).trim().toLowerCase(Locale.ROOT),
          lines[i].substring(colon + 1).trim());
    }
    String body = raw.substring(split + 4);
    if ("chunked".equalsIgnoreCase(headers.get("transfer-encoding"))) {
      body = dechunk(body);
    }
    return new Response(Integer.parseInt(lines[0].split(" ")[1]), headers, body);
  }

  private static String dechunk(String chunked) {
    StringBuilder body = new StringBuilder();
    int pos = 0;
    while (true) {
      int lineEnd = chunked.indexOf("\r\n", pos);
      int size = Integer.parseInt(chunked.substring(pos, lineEnd).trim(), 16);
      if (size == 0) {
        return body.toString();
      }
      body.append(chunked, lineEnd + 2, lineEnd + 2 + size);
      pos = lineEnd + 2 + size + 2;
    }
  }
}
