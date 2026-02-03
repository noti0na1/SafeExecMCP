package executor

import java.io.{File, PrintWriter, FileWriter}
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicInteger

/** Records user-submitted code and execution results to log files.
  *
  * Each call to `record` writes two files under `dir`:
  *   - `NNNN_<timestamp>_<session>.scala` — the submitted code
  *   - `NNNN_<timestamp>_<session>.result` — the execution result
  *
  * NNNN is a zero-padded sequence number so files sort chronologically.
  */
class CodeRecorder(dir: File):
  dir.mkdirs()

  private val counter = new AtomicInteger(1)
  private val tsFormat = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss").withZone(ZoneOffset.UTC)

  def record(code: String, sessionId: String, result: ExecutionResult): Unit =
    val seq = f"${counter.getAndIncrement()}%04d"
    val ts = tsFormat.format(Instant.now())
    val base = s"${seq}_${ts}_$sessionId"

    val codeFile = new PrintWriter(new FileWriter(File(dir, s"$base.scala")))
    try
      codeFile.print(code)
    finally
      codeFile.close()

    val resultFile = new PrintWriter(new FileWriter(File(dir, s"$base.result")))
    try
      val status = if result.success then "success" else "failure"
      resultFile.println(s"status: $status")
      resultFile.println(result.output)
      result.error.foreach { err =>
        resultFile.println(s"Error: $err")
      }
    finally
      resultFile.close()

  def close(): Unit = ()
