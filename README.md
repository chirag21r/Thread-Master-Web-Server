## Thread-Master Web Server (CS Final Year Project)

### Why I built this
As a 4th-year CS student, I wanted a hands-on way to compare concurrency models in real servers. I implemented three HTTP servers from scratch in Java Sockets, then layered in a real-world feature (WebRTC P2P file sharing with server-side signaling) to see how each model behaves under mixed CPU/IO and interactive traffic.

### What this project demonstrates
- Single-threaded vs Multithreaded vs Thread-Pool (RR + Work Stealing)
- Latency/throughput trade-offs, backpressure, and tail latency under load
- Minimal HTTP and WebSocket protocol handling over raw sockets
- WebRTC signaling (server) and P2P file transfer (browser-to-browser)
- How scheduling and queueing shape fairness, responsiveness, and overload behavior
- Practical observability via `/metrics` for data-driven comparisons

### Computer Networks and CS fundamentals covered
- Socket programming (TCP)
  - Blocking `ServerSocket.accept()`; per-connection `Socket` I/O
  - Manual HTTP parsing/writing (request line, headers, status, content-length)
  - WebSocket: HTTP Upgrade, `Sec-WebSocket-Accept` (SHA-1 + Base64), frames, masking, opcodes
- Concurrency models
  - Single-threaded event loop vs thread-per-connection vs thread pool
  - Synchronization-free fast paths, and safe shared-state updates (counters, queues)
- Scheduling and backpressure
  - Round-robin acceptor dispatch; per-worker deques; work stealing to reduce imbalance
  - Bounded queues to avoid unbounded latency and memory growth; explicit load shedding (HTTP 503)
- Performance principles
  - Throughput vs latency vs tail latency (p95) trade-offs
  - Effects of CPU-bound (`/compute`) vs IO-bound (`/io`) workloads
  - Queueing theory intuition (Little’s Law: L = λW) to reason about saturation
- Systems design & reliability
  - Metrics endpoints for visibility; error handling; simple logging
  - Graceful handling when saturated (fast-fail 503) instead of hanging requests

### Socket programming and protocol handling (what you’ll showcase)
- HTTP over raw TCP: request line and header parsing, writing valid responses with `Content-Length`.
- WebSocket handshake: computing `Sec-WebSocket-Accept`, switching protocols, reading client-masked frames.
- WebRTC signaling: server broadcasts SDP offers/answers/ICE candidates; browsers create P2P DataChannel.
- Minimalism is intentional: protocol details are visible and explainable in interviews.

### Repo layout
- `SingleThreaded/` – One request at a time (baseline)
- `Multithreaded/` – One thread per connection + WebSocket signaling + WebRTC demo page
- `ThreadPool/` – Round-robin dispatch to per-worker deques with work stealing + backpressure + signaling + WebRTC demo page
- `scripts/` – Convenience scripts to start/stop all servers

### Endpoints (all servers)
- `/hello` – Simple text; use for quick reachability checks
- `/compute?ms=50` – Busy-wait CPU for `ms` milliseconds (default 50)
- `/io?bytes=1048576` – Stream `bytes` bytes (default 1MB)
- `/metrics` – JSON with basic telemetry
  - Single/Multi: `activeConnections`, `totalRequests`, `avgLatencyMs`, `p95LatencyMs`
  - Thread-Pool adds: `queueDepthSum`, `queueDepthMax`, `queueCapacityPerWorker`, `rejectedRequests`
- `/` (8012/8013) – WebRTC file-share demo UI (signaling over WebSocket `/ws?room=NAME`)

### How to run (Linux)
- Start all three servers:
```
bash scripts/run_servers.sh
```
- Stop all servers and free ports:
```
bash scripts/stop_servers.sh
```

### Open in browser
- Single-threaded: `http://localhost:8011/`
- Multithreaded: `http://localhost:8012/`
- Thread-pool: `http://localhost:8013/`

### WebRTC demo (file sharing)
1) Open two browser tabs to the same server root (both on 8012, or both on 8013).
2) Keep the same room (default `demo`). Click “Connect” in both tabs.
3) Wait until you see “datachannel open” in the log box.
4) In one tab, choose a file and click “Send File”. The other tab shows progress and a download link on completion.

Notes:
- The server only handles signaling (WebSocket `/ws`); the file flows peer-to-peer via WebRTC DataChannel.
- If the UI seems stale, hard refresh both tabs (Ctrl+Shift+R).

### CLI sanity checks
```
curl -s http://localhost:8011/hello
curl -s http://localhost:8012/metrics
curl -s http://localhost:8013/metrics
```

### Quick load experiments (optional)
Install ApacheBench (or use `wrk`/JMeter):
```
sudo apt install -y apache2-utils
ab -n 300 -c 30 http://127.0.0.1:8013/compute?ms=10
watch -n 1 'curl -s http://localhost:8013/metrics'
```
What to observe:
- Single-threaded: rising latency quickly under concurrency
- Multithreaded: better throughput, but thread overhead grows
- Thread-pool: backpressure via bounded queues, `rejectedRequests` under overload, better tail latency stability

### Design choices (short)
- Thread-pool scheduling: Round-robin accept → per-worker deques → work stealing when idle. This reduces lock contention and improves tail latency compared to a single shared queue. Bounded queues provide backpressure; saturation returns HTTP 503.
- Minimal HTTP/WebSocket: Implemented on raw sockets to keep the concurrency mechanisms clear and measurable.
- Metrics: Lightweight counters and rolling latency samples (avg + rough p95).

### Why Round-Robin + Work Stealing?
- RR at the acceptor gives basic fairness and avoids overloading a single worker under bursty arrivals.
- Per-worker deques minimize contention (each worker mostly touches its own queue).
- Work stealing handles skew and burstiness: idle workers pull from the back of busier peers’ deques.
- Compared to a global FIFO queue: fewer shared locks, better cache locality, improved tail latency under imbalance.

### How this is useful
- Learning: a concrete playground to see how concurrency strategies impact latency/throughput.
- Interview-ready: show protocol savvy (HTTP, WebSocket, WebRTC), scheduling decisions, and metrics-driven analysis.
- Benchmarking: swap in different pool sizes/queue capacities and quantify effects via `/metrics` and simple load tools.
- Real-world relevance: signaling paths must stay responsive even under heavy background load—this project demonstrates techniques (backpressure, fair scheduling) to achieve that.

### Interview talking points
- Explain each model’s trade-offs with concrete observations from `/metrics`.
- Show how backpressure avoids meltdown: pool returns 503, queues don’t explode, and signaling stays responsive.
- Real-world angle: WebRTC file-share is sensitive to control-plane latency; highlight how it behaves under `/compute` and `/io` load per model.

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


