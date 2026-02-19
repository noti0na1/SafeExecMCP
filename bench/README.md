# SWE-bench Lite Benchmark for OpenCode + SafeExecMCP

## Prerequisites

- **Docker** (for evaluation only)
- **opencode** v1.2.5+ installed (`~/.opencode/bin/opencode`)
- **Python 3.10+** with `swebench` package (`pip install swebench`)
- **SafeExecMCP JAR** build with `sbt assembly` from the project root

## Quick Start

```bash
# 1. Setup opencode config
opencode

# 2. Run a single instance (default mode: opencode built-in tools, no MCP)
python run_swebench.py --instance astropy__astropy-12907

# 3. Run all instances with MCP-only mode (SafeExecMCP required)
python run_swebench.py --mode mcp_only

# 2. Evaluate the predictions
python eval_swebench.py swebench_runs/<timestamp>/predictions.jsonl
```
