## Thread-Master Web Server

Simple, interview-ready comparison of three Java HTTP servers built on raw sockets, plus a real WebRTC file-share demo to show behavior under mixed load.

### Highlights
- Developed and compared three Java-based HTTP server models: single-threaded, multithreaded, and thread-pool, designed from scratch using Java socket programming, multithreading, and round-robin scheduling to ensure balanced request distribution.
- Implemented a 100-thread pool server in Java, achieving ~7 ms average latency and ~700 req/sec throughput on 1k+ concurrent JMeter requests with 0% error rate.

### What it showcases
- Single-threaded vs Multithreaded vs Thread-Pool (Round-Robin + Work Stealing)
- Latency/throughput trade-offs, backpressure (HTTP 503), and tail latency
- Minimal HTTP + WebSocket implementation over TCP sockets
- WebRTC signaling on the server; browser-to-browser P2P file transfer

### Run
```
bash scripts/run_servers.sh      # start all
bash scripts/stop_servers.sh     # stop all
```
Open: 8011 (single), 8012 (multi), 8013 (thread-pool)

### Endpoints
- /hello – quick text
- /compute?ms=50 – CPU busy work
- /io?bytes=1048576 – stream bytes
- /metrics – JSON telemetry (pool adds queue depth, rejections)
- / (8012/8013) – WebRTC demo UI (signaling at /ws?room=demo)

### WebRTC demo
1) Open two tabs to the same server (both 8012 or both 8013)
2) Click Connect in both (same room)
3) When “datachannel open” appears, choose a file in one tab → Send File

### Why this is useful
- Clear, hands-on comparison of concurrency strategies
- Shows CN/CS fundamentals: TCP sockets, HTTP/WebSocket framing, scheduling, backpressure
- Data-driven discussion via /metrics; easy to load test and explain trade-offs

### Quick checks
```
curl -s http://localhost:8011/hello
curl -s http://localhost:8012/metrics
curl -s http://localhost:8013/metrics
```

### Troubleshooting
- “Address already in use”: run `bash scripts/stop_servers.sh` and retry.
- Nothing at `/metrics`: make sure the right port is running (see `.run_logs/*.log`).
- WebRTC demo not starting: ensure both tabs are on the same server/room, click Connect on both, then send once “datachannel open” appears. Hard-refresh if needed.

### Tech stack
- Java (Sockets, Threads, concurrency utilities)
- WebSocket handshake and framing (server-side)
- WebRTC DataChannel (client-side), STUN for NAT traversal
- ApacheBench / JMeter / wrk for benchmarking

### Future work
- Add TLS (self-signed) for HTTPS/WSS
- Add histograms for p99 latency
- Add TURN server support for tougher NATs
- Swap busy-wait in `/compute` with real CPU-bound tasks (e.g., JSON, hashing)

### Author
Chirag — 4th-year CS student. Built to learn, measure, and explain concurrency models with a tangible, demo-friendly project.

Thread Master Web Server

Overview
- Three Java servers to compare concurrency models:
  - Single-threaded (port 8011)
  - Multithreaded: one thread per connection (port 8012)
  - Thread pool with round-robin dispatch + work stealing (port 8013)
- All expose consistent endpoints and a WebRTC signaling channel (8012, 8013) to demo real-world behavior under load.

Endpoints
- /hello: baseline text
- /compute?ms=50: CPU busy-wait for ms
- /io?bytes=1048576: stream bytes
- /metrics: JSON metrics (active connections, requests, avg/p95 latency; plus queue depth and rejections for thread-pool)
- / (8012, 8013): WebRTC file-share demo using WebSocket signaling at /ws?room=demo

Run locally
1) Single-threaded
   cd SingleThreaded && javac Server.java && java Server
   Open http://localhost:8011/

2) Multithreaded
   cd Multithreaded && javac Server.java && java Server
   Open http://localhost:8012/ (use two tabs, same room, click Connect)

3) Thread-pool (RR + work stealing)
   cd ThreadPool && javac Server.java && java Server
   Open http://localhost:8013/

Batch run (Linux)
- scripts/run_servers.sh will compile and run all three in background.

Scheduling (Pool)
- Acceptor assigns incoming sockets round-robin to per-worker deques.
- Workers process from the front; idle workers steal from the back of peers.
- Bounded queues provide backpressure; full queues trigger 503 responses.

Notes
- Raw sockets with minimal HTTP/WebSocket implementation for clarity.
- WebRTC uses a public STUN server; demo works on localhost without TURN.


