package mcp

import io.circe.*
import io.circe.syntax.*
import executor.{ScalaExecutor, SessionManager, ExecutionResult}

private val InterfaceReference: String =
  val preamble =
    """|IMPORTANT: You must only use the provided interface below to interact with the system.
       |Do not use Java/Scala standard library APIs (java.io, java.nio, scala.io, sys.process, java.net, etc.) to access files, run processes, or make network requests directly.
       |All system interactions must go through the capability-scoped API so that access is properly sandboxed and auditable.
       |
       |The interface is pre-loaded and available in all code executions.
       |
       |""".stripMargin
  val source =
    val stream = classOf[McpServer].getResourceAsStream("/Interface.scala")
    if stream != null then
      try scala.io.Source.fromInputStream(stream)(using scala.io.Codec.UTF8).mkString
      finally stream.close()
    else "(Interface.scala source not found on classpath)"
  preamble + source

/** MCP Server implementation for Scala code execution */
class McpServer:
  private val sessionManager = new SessionManager()
  
  private val tools: List[Tool] = List(
    Tool(
      name = "execute_scala",
      description = Some("Execute a Scala code snippet and return the output. This is stateless - each execution is independent. The library API is pre-loaded: use requestFileSystem(root){ ... }, access(path), grep/grepRecursive/find for files; requestExecPermission(cmds){ exec(...) } for processes; requestNetwork(hosts){ httpGet/httpPost(...) } for HTTP."),
      inputSchema = Json.obj(
        "type" -> "object".asJson,
        "properties" -> Json.obj(
          "code" -> Json.obj(
            "type" -> "string".asJson,
            "description" -> "The Scala code to execute".asJson
          )
        ),
        "required" -> Json.arr("code".asJson)
      )
    ),
    Tool(
      name = "create_repl_session",
      description = Some("Create a new Scala REPL session. Returns a session ID that can be used for subsequent executions."),
      inputSchema = Json.obj(
        "type" -> "object".asJson,
        "properties" -> Json.obj()
      )
    ),
    Tool(
      name = "delete_repl_session",
      description = Some("Delete a Scala REPL session by its ID."),
      inputSchema = Json.obj(
        "type" -> "object".asJson,
        "properties" -> Json.obj(
          "session_id" -> Json.obj(
            "type" -> "string".asJson,
            "description" -> "The ID of the session to delete".asJson
          )
        ),
        "required" -> Json.arr("session_id".asJson)
      )
    ),
    Tool(
      name = "execute_in_session",
      description = Some("Execute Scala code in an existing REPL session. The session maintains state between executions. The library API is pre-loaded: use requestFileSystem(root){ ... }, access(path), grep/grepRecursive/find for files; requestExecPermission(cmds){ exec(...) } for processes; requestNetwork(hosts){ httpGet/httpPost(...) } for HTTP."),
      inputSchema = Json.obj(
        "type" -> "object".asJson,
        "properties" -> Json.obj(
          "session_id" -> Json.obj(
            "type" -> "string".asJson,
            "description" -> "The ID of the REPL session".asJson
          ),
          "code" -> Json.obj(
            "type" -> "string".asJson,
            "description" -> "The Scala code to execute".asJson
          )
        ),
        "required" -> Json.arr("session_id".asJson, "code".asJson)
      )
    ),
    Tool(
      name = "list_sessions",
      description = Some("List all active REPL session IDs."),
      inputSchema = Json.obj(
        "type" -> "object".asJson,
        "properties" -> Json.obj()
      )
    ),
    Tool(
      name = "show_interface",
      description = Some("Show the full capability-scoped API available in the REPL. Call this first to understand what methods you can use. You must only use the provided interface to interact with the system."),
      inputSchema = Json.obj(
        "type" -> "object".asJson,
        "properties" -> Json.obj()
      )
    )
  )
  
  /** Handle a JSON-RPC request and return a response */
  def handleRequest(request: JsonRpcRequest): Option[JsonRpcResponse] =
    request.method match
      case "initialize" =>
        handleInitialize(request)
      case "initialized" =>
        // Notification, no response needed
        None
      case "tools/list" =>
        handleToolsList(request)
      case "tools/call" =>
        handleToolsCall(request)
      case "ping" =>
        Some(JsonRpcResponse.success(request.id, Json.obj()))
      case "notifications/cancelled" =>
        // Notification, no response needed
        None
      case other =>
        Some(JsonRpcResponse.error(
          request.id,
          JsonRpcError.MethodNotFound,
          s"Method not found: $other"
        ))
  
  private def handleInitialize(request: JsonRpcRequest): Option[JsonRpcResponse] =
    val result = InitializeResult(
      protocolVersion = "2024-11-05",
      capabilities = ServerCapabilities(
        tools = Some(ToolsCapability(listChanged = Some(true)))
      ),
      serverInfo = ServerInfo(
        name = "SafeExecMCP",
        version = "0.1.0"
      )
    )
    Some(JsonRpcResponse.success(request.id, result.asJson))
  
  private def handleToolsList(request: JsonRpcRequest): Option[JsonRpcResponse] =
    val result = ToolsListResult(tools)
    Some(JsonRpcResponse.success(request.id, result.asJson))
  
  private def handleToolsCall(request: JsonRpcRequest): Option[JsonRpcResponse] =
    val result = for
      params <- request.params.toRight("Missing params")
      callParams <- params.as[CallToolParams].left.map(_.message)
      toolResult <- callTool(callParams.name, callParams.arguments)
    yield toolResult
    
    result match
      case Right(toolResult) =>
        Some(JsonRpcResponse.success(request.id, toolResult.asJson))
      case Left(error) =>
        Some(JsonRpcResponse.error(
          request.id,
          JsonRpcError.InvalidParams,
          error
        ))
  
  private def callTool(name: String, arguments: Option[Json]): Either[String, CallToolResult] =
    name match
      case "execute_scala" =>
        executeScala(arguments)
      case "create_repl_session" =>
        createReplSession()
      case "delete_repl_session" =>
        deleteReplSession(arguments)
      case "execute_in_session" =>
        executeInSession(arguments)
      case "list_sessions" =>
        listSessions()
      case "show_interface" =>
        showInterface()
      case other =>
        Left(s"Unknown tool: $other")
  
  private def executeScala(arguments: Option[Json]): Either[String, CallToolResult] =
    for
      args <- arguments.toRight("Missing arguments")
      code <- args.hcursor.get[String]("code").left.map(_.message)
    yield
      val result = ScalaExecutor.execute(code)
      formatExecutionResult(result)
  
  private def createReplSession(): Either[String, CallToolResult] =
    val sessionId = sessionManager.createSession()
    Right(CallToolResult(
      content = List(TextContent(s"Created REPL session: $sessionId"))
    ))
  
  private def deleteReplSession(arguments: Option[Json]): Either[String, CallToolResult] =
    for
      args <- arguments.toRight("Missing arguments")
      sessionId <- args.hcursor.get[String]("session_id").left.map(_.message)
    yield
      if sessionManager.deleteSession(sessionId) then
        CallToolResult(content = List(TextContent(s"Deleted session: $sessionId")))
      else
        CallToolResult(
          content = List(TextContent(s"Session not found: $sessionId")),
          isError = Some(true)
        )
  
  private def executeInSession(arguments: Option[Json]): Either[String, CallToolResult] =
    for
      args <- arguments.toRight("Missing arguments")
      sessionId <- args.hcursor.get[String]("session_id").left.map(_.message)
      code <- args.hcursor.get[String]("code").left.map(_.message)
      result <- sessionManager.executeInSession(sessionId, code)
    yield formatExecutionResult(result)
  
  private def listSessions(): Either[String, CallToolResult] =
    val sessions = sessionManager.listSessions()
    val text = if sessions.isEmpty then
      "No active sessions"
    else
      s"Active sessions:\n${sessions.mkString("\n")}"
    Right(CallToolResult(content = List(TextContent(text))))
  
  private def showInterface(): Either[String, CallToolResult] =
    Right(CallToolResult(content = List(TextContent(InterfaceReference))))

  private def formatExecutionResult(result: ExecutionResult): CallToolResult =
    val output = result.error match
      case Some(err) if result.output.nonEmpty =>
        s"${result.output}\n\nError: $err"
      case Some(err) =>
        s"Error: $err"
      case None =>
        if result.output.isEmpty then "(no output)" else result.output
    
    CallToolResult(
      content = List(TextContent(output)),
      isError = if result.success then None else Some(true)
    )
