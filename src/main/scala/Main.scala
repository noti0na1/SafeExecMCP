import mcp.*
import io.circe.*
import io.circe.parser.*
import io.circe.syntax.*
import java.io.{BufferedReader, InputStreamReader, PrintWriter}
import executor.CodeRecorder

/** SafeExecMCP - A Model Context Protocol server for safe Scala code execution */
@main def SafeExecMCP(args: String*): Unit =
  // Parse --record <dir> from CLI args
  val recordDir: Option[String] =
    val idx = args.indexOf("--record")
    if idx >= 0 && idx + 1 < args.length then Some(args(idx + 1))
    else None

  val recorder: Option[CodeRecorder] = recordDir.map { dir =>
    new CodeRecorder(java.io.File(dir))
  }

  val server = new McpServer(recorder)
  val reader = new BufferedReader(new InputStreamReader(System.in))
  val writer = new PrintWriter(System.out, true)

  // Log to stderr so it doesn't interfere with JSON-RPC communication
  def log(msg: String): Unit =
    System.err.println(s"[SafeExecMCP] $msg")

  def printStartupBanner(): Unit =
    val jarPath = scala.util.Try {
      new java.io.File(classOf[McpServer].getProtectionDomain.getCodeSource.getLocation.toURI).getAbsolutePath
    }.getOrElse("<path/to/SafeExecMCP-assembly-0.1.0-SNAPSHOT.jar>")
    val cwd = System.getProperty("user.dir")
    val recordingStatus = recordDir match
      case Some(dir) => s"Recording: ON -> $dir"
      case None      => "Recording: OFF"

    System.err.println(
      s"""
         |╔══════════════════════════════════════════════════════════════════╗
         |║                     SafeExecMCP Server                           ║
         |╠══════════════════════════════════════════════════════════════════╣
         |║  Transport: stdio (JSON-RPC 2.0)                                 ║
         |║  Protocol:  Model Context Protocol (MCP)                         ║
         |║  $recordingStatus
         |╚══════════════════════════════════════════════════════════════════╝
         |
         |Available tools: execute_scala, create_repl_session, execute_in_session,
         |                 delete_repl_session, list_sessions
         |
         |┌─ Claude Desktop configuration (~/.config/claude/claude_desktop_config.json):
         |│
         |│  {
         |│    "mcpServers": {
         |│      "scala-exec": {
         |│        "command": "java",
         |│        "args": ["-jar", "$jarPath"]
         |│      }
         |│    }
         |│  }
         |│
         |└─ Or using sbt:
         |│
         |│  {
         |│    "mcpServers": {
         |│      "scala-exec": {
         |│        "command": "sbt",
         |│        "args": ["--error", "run"],
         |│        "cwd": "$cwd"
         |│      }
         |│    }
         |│  }
         |
         |Server ready. Waiting for JSON-RPC requests on stdin...
         |""".stripMargin)

  printStartupBanner()

  try
    var running = true
    while running do
      val line = reader.readLine()
      if line == null then
        running = false
      else if line.trim.nonEmpty then
        log(s"Received: ${line.take(200)}...")

        parse(line) match
          case Left(error) =>
            val response = JsonRpcResponse.error(
              None,
              JsonRpcError.ParseError,
              s"Parse error: ${error.message}"
            )
            sendResponse(writer, response)

          case Right(json) =>
            json.as[JsonRpcRequest] match
              case Left(error) =>
                val response = JsonRpcResponse.error(
                  None,
                  JsonRpcError.InvalidRequest,
                  s"Invalid request: ${error.message}"
                )
                sendResponse(writer, response)

              case Right(request) =>
                server.handleRequest(request).foreach { response =>
                  sendResponse(writer, response)
                }
  catch
    case e: Exception =>
      log(s"Error: ${e.getMessage}")
      e.printStackTrace(System.err)
  finally
    recorder.foreach(_.close())
    log("Server shutting down...")

def sendResponse(writer: PrintWriter, response: JsonRpcResponse): Unit =
  val json = response.asJson.noSpaces
  System.err.println(s"[SafeExecMCP] Sending: ${json.take(200)}...")
  writer.println(json)
  writer.flush()

