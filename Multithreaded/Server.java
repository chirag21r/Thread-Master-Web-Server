import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;

public class Server {
    private volatile long totalRequests = 0;
    private volatile long totalLatencyNanos = 0;
    private volatile int activeConnections = 0;
    private final long[] lastLatenciesNanos = new long[1024];
    private volatile int latencyWriteIndex = 0;

    // Simple signaling hub: roomId -> set of clients
    private final ConcurrentHashMap<String, CopyOnWriteArraySet<Client>> rooms = new ConcurrentHashMap<>();

    private static class Client {
        final Socket socket;
        final OutputStream out;
        volatile boolean open = true;
        Client(Socket s) throws IOException { this.socket = s; this.out = s.getOutputStream(); }
    }

    private void recordLatency(long nanos) {
        totalLatencyNanos += nanos;
        int idx = latencyWriteIndex++ & (lastLatenciesNanos.length - 1);
        lastLatenciesNanos[idx] = nanos;
    }

    private Map<String, String> parseQuery(String query) {
        Map<String, String> params = new HashMap<>();
        if (query == null || query.isEmpty()) return params;
        String[] pairs = query.split("&");
        for (String p : pairs) {
            int eq = p.indexOf('=');
            if (eq >= 0) {
                String k = java.net.URLDecoder.decode(p.substring(0, eq), StandardCharsets.UTF_8);
                String v = java.net.URLDecoder.decode(p.substring(eq + 1), StandardCharsets.UTF_8);
                params.put(k, v);
            } else if (!p.isEmpty()) {
                params.put(java.net.URLDecoder.decode(p, StandardCharsets.UTF_8), "");
            }
        }
        return params;
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

    private void busyWork(long ms) {
        long duration = ms * 1_000_000L;
        long start = System.nanoTime();
        while (System.nanoTime() - start < duration) {
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

    private String getMetricsJson(String modelName) {
        long totalReq = totalRequests;
        long avgNs = totalReq > 0 ? totalLatencyNanos / totalReq : 0;
        long[] snapshot = lastLatenciesNanos.clone();
        java.util.Arrays.sort(snapshot);
        long p95 = snapshot[(int)(snapshot.length * 0.95) - 1];
        return "{\n" +
                "  \"model\": \"" + modelName + "\",\n" +
                "  \"activeConnections\": " + activeConnections + ",\n" +
                "  \"totalRequests\": " + totalReq + ",\n" +
                "  \"avgLatencyMs\": " + (avgNs / 1_000_000.0) + ",\n" +
                "  \"p95LatencyMs\": " + (p95 / 1_000_000.0) + "\n" +
                "}\n";
    }

    private String getPlaceholderIndexHtml() {
        return "<!doctype html><html><head><meta charset=\"utf-8\"><title>Multithreaded Demo</title>" +
                "<style>body{font-family:sans-serif;margin:2rem}input,button{margin:.25rem}#log{white-space:pre-wrap;border:1px solid #ccc;padding:8px;height:160px;overflow:auto}</style>" +
                "</head><body>" +
                "<h1>Multithreaded Server</h1>" +
                "<p>Endpoints: <a href=\"/hello\">/hello</a>, <a href=\"/compute?ms=50\">/compute</a>, <a href=\"/io?bytes=1048576\">/io</a>, <a href=\"/metrics\">/metrics</a></p>" +
                "<h2>WebRTC File Share (Signaling via WebSocket /ws)</h2>" +
                "<div><label>Room: <input id=\"room\" value=\"demo\"></label><button id=\"connect\">Connect</button></div>" +
                "<div><input type=\"file\" id=\"file\"><button id=\"send\" disabled>Send File</button></div>" +
                "<div id=\"prog\"></div><div id=\"log\"></div>" +
                "<script>\n" +
                "const log=(...a)=>{const el=document.getElementById('log');el.textContent+=a.join(' ')+'\\n';el.scrollTop=el.scrollHeight};\n" +
                "let pc,dc,ws;\n" +
                "const myId=Math.floor(Math.random()*1e9); let otherId=null; let started=false; let recv={name:null,size:0,received:0,chunks:[]};\n" +
                "function setupPeer(){\n" +
                "  pc=new RTCPeerConnection({iceServers:[{urls:'stun:stun.l.google.com:19302'}]});\n" +
                "  pc.onicecandidate=e=>{ if(e.candidate) ws.send(JSON.stringify({type:'candidate',candidate:e.candidate})); };\n" +
                "  pc.ondatachannel=e=>{ dc=e.channel; bindDc(); };\n" +
                "}\n" +
                "function bindDc(){ if(!dc) return; dc.binaryType='arraybuffer'; dc.onopen=()=>{log('datachannel open'); document.getElementById('send').disabled=false}; dc.onmessage=onData; }\n" +
                "async function start(isInitiator){\n" +
                "  if(isInitiator){ dc=pc.createDataChannel('file'); bindDc(); const offer=await pc.createOffer(); await pc.setLocalDescription(offer); ws.send(JSON.stringify({type:'offer',sdp:offer.sdp})); }\n" +
                "}\n" +
                "function maybeStart(){ if(started||otherId===null) return; const initiator=myId>otherId; started=true; start(initiator); }\n" +
                "async function onMsg(ev){\n" +
                "  const m=JSON.parse(ev.data);\n" +
                "  if(m.type==='hello'){ otherId=m.id; maybeStart(); }\n" +
                "  else if(m.type==='offer'){ await pc.setRemoteDescription({type:'offer',sdp:m.sdp}); const ans=await pc.createAnswer(); await pc.setLocalDescription(ans); ws.send(JSON.stringify({type:'answer',sdp:ans.sdp})); }\n" +
                "  else if(m.type==='answer'){ await pc.setRemoteDescription({type:'answer',sdp:m.sdp}); }\n" +
                "  else if(m.type==='candidate'){ try{ await pc.addIceCandidate(m.candidate);}catch(e){log('ice err',e)} }\n" +
                "  else if(m.type==='peer-joined'){ ws.send(JSON.stringify({type:'hello',id:myId})); }\n" +
                "}\n" +
                "function onData(ev){ if(typeof ev.data==='string'){ try{ const j=JSON.parse(ev.data); if(j.t==='file-meta'){ recv={name:j.name,size:j.size,received:0,chunks:[]}; log('meta',recv.name,recv.size); } else { log('recv text',ev.data); } }catch(e){ log('recv text',ev.data);} return } const ab=ev.data; if(!(ab instanceof ArrayBuffer)){ log('unexpected type'); return } const u=new Uint8Array(ab); recv.chunks.push(u); recv.received+=u.length; document.getElementById('prog').textContent=`Recv ${recv.received}/${recv.size}`; if(recv.size>0 && recv.received>=recv.size){ const blob=new Blob(recv.chunks,{type:'application/octet-stream'}); const a=document.createElement('a'); a.href=URL.createObjectURL(blob); a.download=recv.name||'received.bin'; a.textContent=`Download ${a.download}`; document.body.appendChild(a); log('receive complete'); } }\n" +
                "async function sendFile(){ const f=document.getElementById('file').files[0]; if(!f||!dc||dc.readyState!=='open'){return} dc.send(JSON.stringify({t:'file-meta',name:f.name,size:f.size})); const stream=f.stream().getReader(); let sent=0; const total=f.size; while(true){ const {done,value}=await stream.read(); if(done) break; dc.send(value); sent+=value.length; document.getElementById('prog').textContent=`Sent ${sent}/${total}`; } log('send complete'); }\n" +
                "document.getElementById('connect').onclick=()=>{ const room=document.getElementById('room').value||'default'; ws=new WebSocket(`ws://${location.host}/ws?room=${encodeURIComponent(room)}`); ws.onopen=()=>{ log('ws open'); setupPeer(); ws.send(JSON.stringify({type:'hello',id:myId})); }; ws.onmessage=onMsg; ws.onclose=()=>log('ws close'); };\n" +
                "document.getElementById('send').onclick=sendFile;\n" +
                "</script>" +
                "</body></html>";
    }

    private void handleHttpOrWebSocket(Socket clientSocket) {
        long start = System.nanoTime();
        activeConnections++;
        try {
            InputStream in = clientSocket.getInputStream();
            BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            OutputStream rawOut = clientSocket.getOutputStream();
            BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(rawOut, StandardCharsets.UTF_8));

            String requestLine = reader.readLine();
            if (requestLine == null || requestLine.isEmpty()) return;
            Map<String, String> headers = new HashMap<>();
            String line;
            while ((line = reader.readLine()) != null && !line.isEmpty()) {
                int idx = line.indexOf(':');
                if (idx > 0) {
                    headers.put(line.substring(0, idx).trim().toLowerCase(), line.substring(idx + 1).trim());
                }
            }

            String[] parts = requestLine.split(" ", 3);
            String method = parts.length > 0 ? parts[0] : "";
            String target = parts.length > 1 ? parts[1] : "/";
            String path = target;
            String query = "";
            int qIdx = target.indexOf('?');
            if (qIdx >= 0) { path = target.substring(0, qIdx); query = target.substring(qIdx + 1); }
            Map<String, String> queryParams = parseQuery(query);

            boolean isWebSocket = "websocket".equalsIgnoreCase(headers.getOrDefault("upgrade", ""));
            if (isWebSocket && "/ws".equals(path)) {
                doWebSocketHandshakeAndServe(clientSocket, headers, queryParams);
                return;
            }

            if (!"GET".equalsIgnoreCase(method)) {
                respond(writer, 405, "Method Not Allowed", "text/plain", "Method Not Allowed");
                return;
            }

            switch (path) {
                case "/":
                    respond(writer, 200, "OK", "text/html; charset=utf-8", getPlaceholderIndexHtml());
                    break;
                case "/hello":
                    respond(writer, 200, "OK", "text/plain; charset=utf-8", "Hello from multithreaded server\n");
                    break;
                case "/compute": {
                    long ms = parseLongOrDefault(queryParams.get("ms"), 50);
                    busyWork(ms);
                    respond(writer, 200, "OK", "text/plain; charset=utf-8", "compute:"+ms+"ms\n");
                    break;
                }
                case "/io": {
                    long bytes = parseLongOrDefault(queryParams.get("bytes"), 1024 * 1024);
                    String headersOut = "HTTP/1.1 200 OK\r\n" +
                            "Content-Type: application/octet-stream\r\n" +
                            "Content-Length: " + bytes + "\r\n" +
                            "Connection: close\r\n\r\n";
                    writer.write(headersOut);
                    writer.flush();
                    streamBytes(rawOut, bytes);
                    rawOut.flush();
                    break;
                }
                case "/metrics":
                    respond(writer, 200, "OK", "application/json; charset=utf-8", getMetricsJson("multithreaded"));
                    break;
                default:
                    respond(writer, 404, "Not Found", "text/plain; charset=utf-8", "Not Found\n");
            }
        } catch (IOException ex) {
            ex.printStackTrace();
        } finally {
            try { clientSocket.close(); } catch (IOException ignore) {}
            long elapsed = System.nanoTime() - start;
            recordLatency(elapsed);
            activeConnections--;
            totalRequests++;
        }
    }

    private long parseLongOrDefault(String s, long def) {
        if (s == null) return def;
        try { return Long.parseLong(s); } catch (NumberFormatException ex) { return def; }
    }

    private void doWebSocketHandshakeAndServe(Socket clientSocket, Map<String,String> headers, Map<String,String> queryParams) throws IOException {
        String key = headers.get("sec-websocket-key");
        if (key == null) return;
        String accept = computeWebSocketAccept(key);
        OutputStream rawOut = clientSocket.getOutputStream();
        String response = "HTTP/1.1 101 Switching Protocols\r\n" +
                "Upgrade: websocket\r\n" +
                "Connection: Upgrade\r\n" +
                "Sec-WebSocket-Accept: " + accept + "\r\n\r\n";
        rawOut.write(response.getBytes(StandardCharsets.UTF_8));
        rawOut.flush();

        String room = queryParams.getOrDefault("room", "default");
        Client client = null;
        try {
            client = new Client(clientSocket);
            rooms.computeIfAbsent(room, r -> new CopyOnWriteArraySet<>()).add(client);
            // Notify join to the new client
            sendText(client, "{\"type\":\"join\",\"room\":\""+room+"\"}");
            // Notify others so existing peers can re-announce themselves
            broadcast(room, client, "{\"type\":\"peer-joined\"}");
            // Echo/broadcast loop
            readWebSocketLoop(client, room);
        } finally {
            if (client != null) {
                client.open = false;
                CopyOnWriteArraySet<Client> set = rooms.get(room);
                if (set != null) set.remove(client);
                try { clientSocket.close(); } catch (IOException ignore) {}
            }
        }
    }

    private static String computeWebSocketAccept(String key) {
        try {
            String concat = key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";
            MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
            byte[] hash = sha1.digest(concat.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(hash);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private void readWebSocketLoop(Client client, String room) throws IOException {
        InputStream in = client.socket.getInputStream();
        byte[] header = new byte[2];
        while (client.open) {
            int r = readFully(in, header, 0, 2);
            if (r < 2) break;
            int b0 = header[0] & 0xFF;
            int b1 = header[1] & 0xFF;
            boolean fin = (b0 & 0x80) != 0;
            int opcode = b0 & 0x0F;
            boolean masked = (b1 & 0x80) != 0;
            long payloadLen = b1 & 0x7F;
            if (payloadLen == 126) {
                byte[] ext = new byte[2];
                readFully(in, ext, 0, 2);
                payloadLen = ((ext[0] & 0xFF) << 8) | (ext[1] & 0xFF);
            } else if (payloadLen == 127) {
                byte[] ext = new byte[8];
                readFully(in, ext, 0, 8);
                payloadLen = 0;
                for (int i = 0; i < 8; i++) payloadLen = (payloadLen << 8) | (ext[i] & 0xFF);
            }
            byte[] mask = null;
            if (masked) {
                mask = new byte[4];
                readFully(in, mask, 0, 4);
            }
            byte[] payload = new byte[(int)payloadLen];
            if (payloadLen > 0) readFully(in, payload, 0, (int)payloadLen);
            if (masked && payloadLen > 0) {
                for (int i = 0; i < payload.length; i++) payload[i] = (byte)(payload[i] ^ mask[i % 4]);
            }
            if (opcode == 0x8) { // close
                break;
            } else if (opcode == 0x1) { // text
                String text = new String(payload, StandardCharsets.UTF_8);
                broadcast(room, client, text);
            } else if (opcode == 0x9) { // ping
                sendControl(client, 0xA, payload);
            }
            if (!fin) {
                // ignore continuation for simplicity
            }
        }
    }

    private int readFully(InputStream in, byte[] buf, int off, int len) throws IOException {
        int read = 0;
        while (read < len) {
            int r = in.read(buf, off + read, len - read);
            if (r == -1) return read;
            read += r;
        }
        return read;
    }

    private void broadcast(String room, Client sender, String message) {
        CopyOnWriteArraySet<Client> set = rooms.get(room);
        if (set == null) return;
        for (Client c : set) {
            if (c != sender && c.open) {
                sendText(c, message);
            }
        }
    }

    private synchronized void sendText(Client client, String text) {
        try {
            byte[] payload = text.getBytes(StandardCharsets.UTF_8);
            OutputStream out = client.out;
            int b0 = 0x80 | 0x1; // FIN + text
            out.write(b0);
            if (payload.length <= 125) {
                out.write(payload.length);
            } else if (payload.length <= 0xFFFF) {
                out.write(126);
                out.write((payload.length >>> 8) & 0xFF);
                out.write(payload.length & 0xFF);
            } else {
                out.write(127);
                long len = payload.length;
                for (int i = 7; i >= 0; i--) out.write((int)(len >>> (8 * i)) & 0xFF);
            }
            out.write(payload);
            out.flush();
        } catch (IOException ignore) {}
    }

    private synchronized void sendControl(Client client, int opcode, byte[] payload) {
        try {
            OutputStream out = client.out;
            out.write(0x80 | opcode);
            int len = payload == null ? 0 : payload.length;
            out.write(len);
            if (len > 0) out.write(payload);
            out.flush();
        } catch (IOException ignore) {}
    }

    public static void main(String[] args) {
        int port = 8012;
        Server server = new Server();

        try {
            ServerSocket serverSocket = new ServerSocket(port);
            System.out.println("[Multithreaded] Server is listening on port " + port);
            while (true) {
                Socket clientSocket = serverSocket.accept();
                Thread thread = new Thread(() -> server.handleHttpOrWebSocket(clientSocket));
                thread.start();
            }
        } catch (IOException ex) {
            ex.printStackTrace();
        }
    }
}
