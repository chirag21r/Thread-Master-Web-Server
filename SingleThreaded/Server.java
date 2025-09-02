import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.UnknownHostException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

public class Server {
    private volatile long totalRequests = 0;
    private volatile long totalLatencyNanos = 0;
    private volatile int activeConnections = 0;
    private final long[] lastLatenciesNanos = new long[1024];
    private volatile int latencyWriteIndex = 0;

    public void run() throws IOException, UnknownHostException{
        int port = 8011;
        ServerSocket socket = new ServerSocket(port);
        System.out.println("[SingleThreaded] Server is listening on port: "+port);
        while(true){
            Socket acceptedConnection = socket.accept();
            long start = System.nanoTime();
            activeConnections++;
            try {
                handleHttpConnection(acceptedConnection);
            } catch (Exception ex) {
                ex.printStackTrace();
            } finally {
                try { acceptedConnection.close(); } catch (IOException ignore) {}
                long elapsed = System.nanoTime() - start;
                recordLatency(elapsed);
                activeConnections--;
                totalRequests++;
            }
        }
    }

    private void recordLatency(long nanos) {
        totalLatencyNanos += nanos;
        int idx = latencyWriteIndex++ & (lastLatenciesNanos.length - 1);
        lastLatenciesNanos[idx] = nanos;
    }

    private void handleHttpConnection(Socket socket) throws IOException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
        OutputStream rawOut = socket.getOutputStream();
        BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(rawOut, StandardCharsets.UTF_8));

        String requestLine = reader.readLine();
        if (requestLine == null || requestLine.isEmpty()) {
            return;
        }

        // Read headers (ignored for now)
        String line;
        while ((line = reader.readLine()) != null && !line.isEmpty()) {
            // ignore
        }

        String[] parts = requestLine.split(" ", 3);
        String method = parts.length > 0 ? parts[0] : "";
        String target = parts.length > 1 ? parts[1] : "/";

        String path = target;
        String query = "";
        int qIdx = target.indexOf('?');
        if (qIdx >= 0) {
            path = target.substring(0, qIdx);
            query = target.substring(qIdx + 1);
        }
        Map<String, String> queryParams = parseQuery(query);

        if (!"GET".equalsIgnoreCase(method)) {
            respond(writer, 405, "Method Not Allowed", "text/plain", "Method Not Allowed");
            return;
        }

        switch (path) {
            case "/":
                String html = getPlaceholderIndexHtml();
                respond(writer, 200, "OK", "text/html; charset=utf-8", html);
                break;
            case "/hello":
                respond(writer, 200, "OK", "text/plain; charset=utf-8", "Hello from single-threaded server\n");
                break;
            case "/compute": {
                long ms = parseLongOrDefault(queryParams.get("ms"), 50);
                busyWork(ms);
                respond(writer, 200, "OK", "text/plain; charset=utf-8", "compute:"+ms+"ms\n");
                break;
            }
            case "/io": {
                long bytes = parseLongOrDefault(queryParams.get("bytes"), 1024 * 1024);
                String headers = "HTTP/1.1 200 OK\r\n" +
                        "Content-Type: application/octet-stream\r\n" +
                        "Content-Length: " + bytes + "\r\n" +
                        "Connection: close\r\n\r\n";
                writer.write(headers);
                writer.flush();
                streamBytes(rawOut, bytes);
                rawOut.flush();
                break;
            }
            case "/metrics": {
                String body = getMetricsJson();
                respond(writer, 200, "OK", "application/json; charset=utf-8", body);
                break;
            }
            default:
                respond(writer, 404, "Not Found", "text/plain; charset=utf-8", "Not Found\n");
        }
    }

    private void respond(BufferedWriter writer, int status, String statusText, String contentType, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        writer.write("HTTP/1.1 " + status + " " + statusText + "\r\n");
        writer.write("Content-Type: " + contentType + "\r\n");
        writer.write("Content-Length: " + bytes.length + "\r\n");
        writer.write("Connection: close\r\n\r\n");
        writer.write(body);
        writer.flush();
    }

    private Map<String, String> parseQuery(String query) {
        Map<String, String> params = new HashMap<>();
        if (query == null || query.isEmpty()) return params;
        String[] pairs = query.split("&");
        for (String p : pairs) {
            int eq = p.indexOf('=');
            if (eq >= 0) {
                String k = URLDecoder.decode(p.substring(0, eq), StandardCharsets.UTF_8);
                String v = URLDecoder.decode(p.substring(eq + 1), StandardCharsets.UTF_8);
                params.put(k, v);
            } else if (!p.isEmpty()) {
                params.put(URLDecoder.decode(p, StandardCharsets.UTF_8), "");
            }
        }
        return params;
    }

    private long parseLongOrDefault(String s, long def) {
        if (s == null) return def;
        try { return Long.parseLong(s); } catch (NumberFormatException ex) { return def; }
    }

    private void busyWork(long ms) {
        long duration = ms * 1_000_000L;
        long start = System.nanoTime();
        while (System.nanoTime() - start < duration) {
            // busy loop
        }
    }

    private void streamBytes(OutputStream out, long bytes) throws IOException {
        byte[] buf = new byte[8192];
        for (int i = 0; i < buf.length; i++) buf[i] = 'a';
        long remaining = bytes;
        while (remaining > 0) {
            int toWrite = (int)Math.min(remaining, buf.length);
            out.write(buf, 0, toWrite);
            remaining -= toWrite;
        }
    }

    private String getMetricsJson() {
        long totalReq = totalRequests;
        long avgNs = totalReq > 0 ? totalLatencyNanos / totalReq : 0;
        long[] snapshot = lastLatenciesNanos.clone();
        // compute a simple p95 over the snapshot
        java.util.Arrays.sort(snapshot);
        long p95 = snapshot[(int)(snapshot.length * 0.95) - 1];
        return "{\n" +
                "  \"model\": \"single-threaded\",\n" +
                "  \"activeConnections\": " + activeConnections + ",\n" +
                "  \"totalRequests\": " + totalReq + ",\n" +
                "  \"avgLatencyMs\": " + (avgNs / 1_000_000.0) + ",\n" +
                "  \"p95LatencyMs\": " + (p95 / 1_000_000.0) + "\n" +
                "}\n";
    }

    private String getPlaceholderIndexHtml() {
        return "<!doctype html><html><head><meta charset=\"utf-8\"><title>Thread Master Demo</title></head><body>" +
                "<h1>Thread Master Web Server</h1>" +
                "<p>This is the single-threaded server (port 8011). Endpoints:</p>" +
                "<ul>" +
                "<li><a href=\"/hello\">/hello</a></li>" +
                "<li><a href=\"/compute?ms=50\">/compute?ms=50</a></li>" +
                "<li><a href=\"/io?bytes=1048576\">/io?bytes=1048576</a></li>" +
                "<li><a href=\"/metrics\">/metrics</a></li>" +
                "</ul>" +
                "<p>WebRTC demo page will be added here and in other servers.</p>" +
                "</body></html>";
    }

    public static void main(String[] args){
        Server server = new Server();
        try{
            server.run();
        }catch(Exception ex){
            ex.printStackTrace();
        }
    }

}
