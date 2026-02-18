package core

import executor.CodeRecorder
import config.Config
import library.LlmConfig

case class Context(
  recorder: Option[CodeRecorder],
  strictMode: Boolean,
  classifiedPaths: Set[String] = Set.empty,
  llmConfig: Option[LlmConfig] = None,
  wrappedCode: Boolean = true,
)

object Context:

  def usingContext[R](config: Config)(op: Context ?=> R): R  =
    val recorder: Option[CodeRecorder] = config.recordPath.map: dir =>
      new CodeRecorder(java.io.File(dir))
    val myCtx = Context(recorder, config.strictMode, config.classifiedPaths, config.llmConfig, config.wrappedCode)
    try op(using myCtx)
    finally
      recorder.foreach(_.close())

  def ctx(using c: Context): Context = c

end Context
