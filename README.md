# SafeExecMCP

An [MCP](https://modelcontextprotocol.io/) server that executes Scala 3 code in a sandboxed environment via an embedded REPL. It enforces security through a capability-based system using Scala 3's experimental [capture checking](https://docs.scala-lang.org/scala3/reference/experimental/cc.html), preventing unauthorized access to the file system, processes, and network.

Supports both stateless one-shot execution and stateful sessions that persist definitions across calls.

## Quick Start

```bash
sbt assembly
java -jar target/scala-3.8.2-RC1/SafeExecMCP-assembly-0.1.0-SNAPSHOT.jar
```

To enable execution logging:

```bash
java -jar target/scala-3.8.2-RC1/SafeExecMCP-assembly-0.1.0-SNAPSHOT.jar --record ./log
```

## MCP Client Configuration

**Claude Desktop** (`~/.config/claude/claude_desktop_config.json`):

```json
{
  "mcpServers": {
    "scala-exec": {
      "command": "java",
      "args": ["-jar", "/path/to/SafeExecMCP-assembly-0.1.0-SNAPSHOT.jar"]
    }
  }
}
```

<details>
<summary>Using sbt directly (for development)</summary>

```json
{
  "mcpServers": {
    "scala-exec": {
      "command": "sbt",
      "args": ["--error", "run"],
      "cwd": "/path/to/SafeExecMCP"
    }
  }
}
```

</details>

## Tools

| Tool | Parameters | Description |
|------|-----------|-------------|
| `execute_scala` | `code` | Execute a Scala snippet in a fresh REPL (stateless) |
| `create_repl_session` | — | Create a persistent REPL session, returns `session_id` |
| `execute_in_session` | `session_id`, `code` | Execute code in an existing session (stateful) |
| `list_sessions` | — | List active session IDs |
| `delete_repl_session` | `session_id` | Delete a session |
| `show_interface` | — | Show the full capability API reference |

### Example: Stateful Session

```
1. create_repl_session          → session_id: "abc-123"
2. execute_in_session(code: "val x = 42")   → x: Int = 42
3. execute_in_session(code: "x * 2")        → val res0: Int = 84
4. delete_repl_session(session_id: "abc-123")
```

## Security Model

All user code is validated before execution. Direct use of `java.io`, `java.nio`, `java.net`, `ProcessBuilder`, `Runtime.getRuntime`, reflection APIs, and other unsafe APIs is rejected. Instead, users access these resources through a capability-based API that is automatically injected into every REPL session.

### Capability API

The API exposes three capability request methods, each scoping access to a block:

```scala
// File system — scoped to a root directory
requestFileSystem("/tmp/work") {
  val f = access("data.txt")
  f.write("hello")
  val lines = f.readLines()
  grep("data.txt", "hello")
  find(".", "*.txt")
}

// Process execution — scoped to an allowlist of commands
requestExecPermission(Set("ls", "cat")) {
  val result = exec("ls", List("-la"))
  println(result.stdout)
}

// Network — scoped to an allowlist of hosts
requestNetwork(Set("api.example.com")) {
  val body = httpGet("https://api.example.com/data")
  httpPost("https://api.example.com/submit", """{"key":"value"}""")
}
```

Capabilities cannot escape their scoped block — this is enforced at compile time by Scala 3's capture checker.

### Validation

Code is checked against 40+ forbidden patterns before execution, covering:

- File I/O bypass (`java.io.*`, `java.nio.*`, `scala.io.*`)
- Process bypass (`ProcessBuilder`, `Runtime.getRuntime`, `scala.sys.process`)
- Network bypass (`java.net.*`, `javax.net.*`, `HttpClient`)
- Reflection (`getDeclaredMethod`, `setAccessible`, `Class.forName`)
- JVM internals (`sun.misc.*`, `jdk.internal.*`)
- Capture checking escape (`caps.unsafe`, `unsafeAssumePure`, `.asInstanceOf`)
- System control (`System.exit`, `System.setProperty`, `new Thread`)
- Class loading (`ClassLoader`, `URLClassLoader`)

## Code Recording

Pass `--record <dir>` to log every execution to disk, including the code and the full output.

## Development

```bash
sbt clean                      # Clean build artifacts
sbt compile                    # Compile
sbt test                       # Run all tests
sbt "testOnly *McpServerSuite" # Run a single suite
sbt run                        # Run the server locally
sbt "run --record ./log"       # Run with logging enabled
sbt assembly                   # Build fat JAR
```

## Requirements

- JDK 17+
- sbt 1.12+

## License

MIT
