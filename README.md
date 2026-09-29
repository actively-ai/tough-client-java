# Tough Client (Java)

Java port of [tough-client](https://github.com/actively-ai/tough-client).

- `Main.java` — the proxy server. Listens on `http://localhost:8000/completion` and forwards requests to the upstream tough server.
- `Simulator.java` — runs two clients against the server and reports success/failure stats.

## Requirements

- Java 17 or later
- Maven 3.8 or later

On macOS: `brew install openjdk@17 maven`

## Installation

```bash
mvn compile
```

## Usage

```bash
# Run the server (port 8000)
mvn -q compile exec:java

# Run the simulator (in a second terminal)
mvn -q compile exec:java -Dexec.mainClass=ai.actively.toughclient.Simulator -Dexec.args="<your_name>"
```

The simulator takes the same arguments as the Python version: `USER_ID [URL] [DURATION]`, e.g.

```bash
mvn -q compile exec:java -Dexec.mainClass=ai.actively.toughclient.Simulator \
  -Dexec.args="<your_name> http://localhost:8000/completion 60"
```
