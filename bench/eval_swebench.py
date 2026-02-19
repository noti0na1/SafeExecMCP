#!/usr/bin/env python3
"""
Evaluate SWE-bench Lite predictions using the official swebench harness.

Requires: pip install swebench, Docker running.

Usage:
    python eval_swebench.py predictions.jsonl                      # Evaluate all
    python eval_swebench.py predictions.jsonl --instance django__django-11039  # Single instance
    python eval_swebench.py predictions.jsonl --max-workers 4      # Parallel workers
    python eval_swebench.py --validate                             # Validate setup with gold patch
"""

import argparse
import json
import subprocess
import sys
from pathlib import Path


DATASET = "princeton-nlp/SWE-bench_Lite"


def run_evaluation(
    predictions_path: str,
    run_id: str,
    instance_ids: list[str] | None = None,
    max_workers: int = 1,
    namespace: str = "",
    cache_level: str = "instance",
):
    """Run the swebench evaluation harness."""
    cmd = [
        sys.executable,
        "-m",
        "swebench.harness.run_evaluation",
        "--dataset_name",
        DATASET,
        "--predictions_path",
        predictions_path,
        "--max_workers",
        str(max_workers),
        "--run_id",
        run_id,
        "--cache_level",
        cache_level,
    ]

    if namespace is not None:
        cmd.extend(["--namespace", namespace])

    if instance_ids:
        cmd.extend(["--instance_ids"] + instance_ids)

    print(f"Running: {' '.join(cmd)}")
    print()
    return subprocess.run(cmd)


def print_results(run_id: str):
    """Print evaluation results summary."""
    results_dir = Path("evaluation_results") / run_id
    results_file = results_dir / "results.json"

    if not results_file.exists():
        # Try the predictions filename-based path pattern
        for p in Path("evaluation_results").rglob("results.json"):
            results_file = p
            results_dir = p.parent
            break

    if not results_file.exists():
        print(f"No results found at {results_file}")
        return

    results = json.loads(results_file.read_text())
    print("\n" + "=" * 60)
    print("EVALUATION RESULTS")
    print("=" * 60)

    if isinstance(results, dict):
        for key, value in results.items():
            if isinstance(value, dict):
                print(f"\n{key}:")
                for k, v in value.items():
                    print(f"  {k}: {v}")
            else:
                print(f"{key}: {value}")
    else:
        print(json.dumps(results, indent=2))

    # Also check for per-instance results
    instance_results = results_dir / "instance_results.jsonl"
    if instance_results.exists():
        resolved = 0
        total = 0
        for line in instance_results.read_text().splitlines():
            if not line.strip():
                continue
            entry = json.loads(line)
            total += 1
            status = entry.get("resolved", entry.get("status", ""))
            if status is True or status == "RESOLVED_FULL":
                resolved += 1
                print(f"  PASS: {entry.get('instance_id', '?')}")
            else:
                print(f"  FAIL: {entry.get('instance_id', '?')}")
        if total:
            print(f"\nResolved: {resolved}/{total} ({100 * resolved / total:.1f}%)")


def get_instance_ids_from_predictions(predictions_path: str) -> list[str]:
    """Extract instance IDs from a predictions file."""
    ids = []
    with open(predictions_path) as f:
        for line in f:
            line = line.strip()
            if line:
                try:
                    ids.append(json.loads(line)["instance_id"])
                except (json.JSONDecodeError, KeyError):
                    pass
    return ids


def main():
    parser = argparse.ArgumentParser(
        description="Evaluate SWE-bench Lite predictions"
    )
    parser.add_argument(
        "predictions",
        nargs="?",
        help="Path to predictions.jsonl file",
    )
    parser.add_argument(
        "--instance",
        type=str,
        action="append",
        dest="instance_ids",
        help="Evaluate specific instance(s) only (can repeat)",
    )
    parser.add_argument(
        "--max-workers",
        type=int,
        default=1,
        help="Number of parallel Docker workers (default: 1)",
    )
    parser.add_argument(
        "--run-id",
        type=str,
        default=None,
        help="Run ID for results directory (default: auto-generated)",
    )
    parser.add_argument(
        "--namespace",
        type=str,
        default="",
        help="Docker namespace (default: '' for local builds, needed on macOS ARM)",
    )
    parser.add_argument(
        "--cache-level",
        type=str,
        default="instance",
        choices=["none", "base", "env", "instance"],
        help="Docker cache level (default: instance)",
    )
    parser.add_argument(
        "--validate",
        action="store_true",
        help="Validate setup by running gold patches on a test instance",
    )
    parser.add_argument(
        "--results-only",
        type=str,
        metavar="RUN_ID",
        help="Just print results for a previous run ID (no evaluation)",
    )
    args = parser.parse_args()

    # Just print previous results
    if args.results_only:
        print_results(args.results_only)
        return

    # Validate mode
    if args.validate:
        run_id = args.run_id or "validate-gold"
        instance_ids = args.instance_ids or ["sympy__sympy-20590"]
        print(f"Validating setup with gold patches for: {instance_ids}")
        result = run_evaluation(
            "gold", run_id, instance_ids, args.max_workers, args.namespace, args.cache_level
        )
        if result.returncode == 0:
            print_results(run_id)
        sys.exit(result.returncode)

    # Normal evaluation
    if not args.predictions:
        parser.error("predictions file is required (or use --validate)")

    predictions_path = args.predictions
    if not Path(predictions_path).exists():
        print(f"ERROR: predictions file not found: {predictions_path}")
        sys.exit(1)

    # Show what we're evaluating
    all_ids = get_instance_ids_from_predictions(predictions_path)
    eval_ids = args.instance_ids or all_ids
    print(f"Predictions file: {predictions_path}")
    print(f"Instances in file: {len(all_ids)}")
    print(f"Instances to evaluate: {len(eval_ids)}")
    print()

    run_id = args.run_id or Path(predictions_path).stem
    result = run_evaluation(
        predictions_path,
        run_id,
        args.instance_ids,
        args.max_workers,
        args.namespace,
        args.cache_level,
    )

    if result.returncode == 0:
        print_results(run_id)

    sys.exit(result.returncode)


if __name__ == "__main__":
    main()
