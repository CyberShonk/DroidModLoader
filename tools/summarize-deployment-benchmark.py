#!/usr/bin/env python3
"""Summarize repeated DML deployment timings for SAF and direct-path builds."""

from __future__ import annotations

import argparse
import json
import statistics
from dataclasses import asdict, dataclass


@dataclass(frozen=True)
class TimingSummary:
    samples_ms: list[float]
    median_ms: float
    mean_ms: float
    minimum_ms: float
    maximum_ms: float


def parse_samples(value: str) -> list[float]:
    try:
        samples = [float(item.strip()) for item in value.split(",") if item.strip()]
    except ValueError as error:
        raise argparse.ArgumentTypeError(f"Invalid millisecond value: {error}") from error

    if len(samples) < 3:
        raise argparse.ArgumentTypeError("Provide at least three comma-separated timings.")
    if any(sample <= 0 for sample in samples):
        raise argparse.ArgumentTypeError("All timings must be greater than zero.")
    return samples


def summarize(samples: list[float]) -> TimingSummary:
    return TimingSummary(
        samples_ms=samples,
        median_ms=statistics.median(samples),
        mean_ms=statistics.fmean(samples),
        minimum_ms=min(samples),
        maximum_ms=max(samples),
    )


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Compare repeated SAF-build and direct-build deployment durations."
    )
    parser.add_argument("--workload", required=True, help="Human-readable workload name.")
    parser.add_argument("--saf-ms", required=True, type=parse_samples)
    parser.add_argument("--direct-ms", required=True, type=parse_samples)
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    saf = summarize(args.saf_ms)
    direct = summarize(args.direct_ms)
    speedup = saf.median_ms / direct.median_ms
    reduction = (1.0 - direct.median_ms / saf.median_ms) * 100.0

    result = {
        "workload": args.workload,
        "saf": asdict(saf),
        "direct": asdict(direct),
        "median_speedup_ratio": speedup,
        "median_time_reduction_percent": reduction,
    }

    print(json.dumps(result, indent=2))


if __name__ == "__main__":
    main()
