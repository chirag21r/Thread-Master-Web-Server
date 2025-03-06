# Thread-Master-Web-Server

*A high-performance Java-based web server designed to handle concurrent client requests efficiently*  

![Java](https://img.shields.io/badge/Java-17%2B-blue?style=flat&logo=openjdk)
![License](https://img.shields.io/badge/License-MIT-green)
[![PRs Welcome](https://img.shields.io/badge/PRs-Welcome-brightgreen)](https://github.com/AlphaDecodeX/MultithreadedWebServer/pulls)

## 🌟 Features  
- **Concurrent Request Handling**: Thread pooling via `ExecutorService` for optimal resource management  
- **Configurable Architecture**: Dynamic thread pool sizing and port configuration  
- **Graceful Shutdown**: Custom shutdown hook for safe resource cleanup  
- **MIME Type Support**: Automatic content-type detection for common file formats  
- **Request Logging**: Detailed request/response tracking with timestamped logs  
- **Error Handling**: Robust HTTP 404/500 error responses with user-friendly pages  

## 🛠️ Technical Architecture  
```mermaid
graph TD
    A[Client Request] --> B{Server Socket}
    B --> C[Thread Pool]
    C --> D[Worker Thread]
    D --> E{Request Parser}
    E --> F[File Handler]
    E --> G[Error Handler]
    F --> H[HTTP Response]
    G --> H
    H --> I[Client]

📦 Installation & Usage
Prerequisites: 

 -Java 17+
 -Maven

# Clone the repository
git clone https://github.com/AlphaDecodeX/MultithreadedWebServer.git

# Compile and package
mvn clean package

# Run the server (default: port 8080, 10 threads)
java -jar target/MultithreadedWebServer.jar

# Custom configuration
java -jar target/MultithreadedWebServer.jar --port 9090 --maxThreads 25

Configuration
# server.properties
port=8080
maxThreads=10
webRoot=./www
logFile=server.log

� Testing

# Test concurrent requests
curl -v http://localhost:8080/index.html

# Stress test (using Apache Bench)
ab -n 1000 -c 50 http://localhost:8080/testfile.txt

 Key Design Decisions
Thread Pooling: Utilized ExecutorService over raw threads for better resource management and scalability

Non-Blocking IO: Implemented hybrid approach combining thread pooling with Java NIO for efficient I/O operations

Configurability: Separated server parameters into properties file for environment-specific deployments

Security: Implemented path normalization to prevent directory traversal attacks

Performance: Optimized file serving using buffered streams and proper connection headers

🧑💻 Tech Stack
Java
ExecutorService
NIO
Maven
