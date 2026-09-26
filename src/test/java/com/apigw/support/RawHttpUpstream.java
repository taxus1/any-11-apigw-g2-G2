package com.apigw.support;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 切片测试用的「裸」HTTP 上游：直接按字节发响应，好精确控制 Content-Length、
 * Transfer-Encoding、慢响应、连接拒绝等场景（用成熟服务器反而不好构造）。
 *
 * 行为：
 * - /echo     回 200，把收到的请求头以 JSON 回显；故意带一个上游自己的 Content-Length；
 * - /slow     先读请求头，然后挂着不回（测 504）；
 * - /upstream-500 回上游自己的 500 错误页（应原样透传，且标记成转发成功而非网关错误）；
 * - 其它路径   回 404（上游的 404，与「网关没找着路」要区分开）。
 */
public class RawHttpUpstream implements AutoCloseable {

    private final ServerSocket server;
    private final ExecutorService pool = Executors.newCachedThreadPool();
    private final Thread acceptor;
    private volatile boolean stopped;

    public RawHttpUpstream() throws IOException {
        this.server = new ServerSocket(0);
        this.acceptor = new Thread(this::acceptLoop, "raw-upstream-accept");
        this.acceptor.setDaemon(true);
        this.acceptor.start();
    }

    public int port() {
        return server.getLocalPort();
    }

    public String base() {
        return "http://127.0.0.1:" + port();
    }

    private void acceptLoop() {
        while (!stopped) {
            try {
                Socket socket = server.accept();
                pool.submit(() -> handle(socket));
            } catch (IOException e) {
                if (!stopped) {
                    e.printStackTrace();
                }
                return;
            }
        }
    }

    private void handle(Socket socket) {
        try (socket) {
            socket.setSoTimeout(5000);
            InputStream in = socket.getInputStream();
            byte[] head = readUntilHeadersEnd(in);
            String headText = new String(head, StandardCharsets.ISO_8859_1);
            String[] lines = headText.split("\r\n");
            String requestLine = lines[0];
            String path = requestLine.split(" ")[1];

            Map<String, String> got = new LinkedHashMap<>();
            for (int i = 1; i < lines.length; i++) {
                int colon = lines[i].indexOf(':');
                if (colon > 0) {
                    got.put(lines[i].substring(0, colon).trim(),
                            lines[i].substring(colon + 1).trim());
                }
            }
            int contentLength = parseContentLength(got.get("Content-Length"));
            byte[] requestBody = in.readNBytes(contentLength);

            OutputStream out = new BufferedOutputStream(socket.getOutputStream());
            if (path.startsWith("/slow")) {
                // 读到请求头后挂住不回，直到连接被客户端关掉
                try {
                    Thread.sleep(30_000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return;
            }
            if (endsWithSegment(path, "/echo-body")) {
                // 把收到的请求体原样回吐，并带上收到的 Content-Type
                writeResponse(out, 200, "OK",
                        Map.of("Content-Type", got.getOrDefault("Content-Type", "application/octet-stream")),
                        requestBody);
                return;
            }
            if (endsWithSegment(path, "/upstream-500")) {
                writeResponse(out, 500, "Internal Server Error",
                        Map.of("Content-Type", "text/plain", "X-Upstream", "boom"),
                        "upstream exploded".getBytes(StandardCharsets.UTF_8));
                return;
            }
            if (endsWithSegment(path, "/echo")) {
                String headersJson = got.entrySet().stream()
                        .map(e -> "\"" + e.getKey() + "\":\"" + e.getValue().replace("\"", "'") + "\"")
                        .reduce((a, b) -> a + "," + b).orElse("");
                byte[] body = ("{\"path\":\"" + path + "\",\"headers\":{" + headersJson + "}}")
                        .getBytes(StandardCharsets.UTF_8);
                writeResponse(out, 200, "OK",
                        Map.of("Content-Type", "application/json",
                                "X-Upstream", "8099",
                                "X-Debug", "dbg"),
                        body);
                return;
            }
            writeResponse(out, 404, "Not Found",
                    Map.of("Content-Type", "text/plain"),
                    "upstream 404".getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) {
            // 测试上游，读/写断了就算了
        }
    }

    /** 路径以某段结尾：/a/echo 算，/echox 不算（与前缀的段边界同一套道理）。 */
    private static boolean endsWithSegment(String path, String suffix) {
        int q = path.indexOf('?');
        String p = q < 0 ? path : path.substring(0, q);
        return p.equals(suffix) || p.endsWith(suffix);
    }

    private static int parseContentLength(String v) {
        if (v == null) {
            return 0;
        }
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static byte[] readUntilHeadersEnd(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        int matched = 0;
        byte[] marker = "\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1);
        int b;
        while ((b = in.read()) != -1) {
            buf.write(b);
            if (b == marker[matched]) {
                matched++;
                if (matched == marker.length) {
                    break;
                }
            } else {
                matched = (b == marker[0]) ? 1 : 0;
            }
        }
        return buf.toByteArray();
    }

    private static void writeResponse(OutputStream out, int status, String reason,
                                      Map<String, String> headers, byte[] body) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("HTTP/1.1 ").append(status).append(' ').append(reason).append("\r\n");
        // 上游故意给一个与我们配置动作无关的、它自己的 Content-Length 与 Keep-Alive 头，
        // 用来验证网关不照抄这些绑定具体报文的头
        sb.append("Connection: keep-alive\r\n");
        sb.append("Content-Length: ").append(body.length).append("\r\n");
        for (Map.Entry<String, String> e : headers.entrySet()) {
            sb.append(e.getKey()).append(": ").append(e.getValue()).append("\r\n");
        }
        sb.append("\r\n");
        out.write(sb.toString().getBytes(StandardCharsets.ISO_8859_1));
        out.write(body);
        out.flush();
    }

    /** 一个可以确定没人监听的端口（关掉即释放，立刻拿它做连接拒绝用例）。 */
    public static int freeClosedPort() throws IOException {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }

    /** 最近各连接回显的头暂不在实例间共享；如需断言，直接解析 /echo 响应体。 */
    public static List<String> noop() {
        return new ArrayList<>();
    }

    @Override
    public void close() {
        stopped = true;
        try {
            server.close();
        } catch (IOException ignored) {
        }
        pool.shutdownNow();
    }
}
