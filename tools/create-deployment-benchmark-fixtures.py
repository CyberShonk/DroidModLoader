#!/usr/bin/env python3
"""Create deterministic DML deployment benchmark archives.

The archives use ZIP_STORED so archive compression does not distort the amount
of data DML must extract and deploy. File content is deterministic, allowing the
same fixtures to be recreated and verified on different machines.
"""

from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
from zipfile import ZIP_STORED, ZipFile

CHUNK_SIZE = 64 * 1024


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Create deterministic many-small-file and large-file DML benchmark archives."
    )
    parser.add_argument(
        "--output-dir",
        type=Path,
        required=True,
        help="Directory that will receive the ZIP files and manifest.",
    )
    parser.add_argument("--small-file-count", type=int, default=2000)
    parser.add_argument("--small-file-bytes", type=int, default=4096)
    parser.add_argument("--large-file-count", type=int, default=4)
    parser.add_argument("--large-file-bytes", type=int, default=32 * 1024 * 1024)
    return parser.parse_args()


def validate_positive(name: str, value: int) -> None:
    if value <= 0:
        raise SystemExit(f"{name} must be greater than zero, received {value}.")


def deterministic_chunk(label: str, index: int, chunk_index: int) -> bytes:
    seed = f"dml-benchmark:{label}:{index}:{chunk_index}".encode("utf-8")
    digest = hashlib.sha256(seed).digest()
    repeats = (CHUNK_SIZE + len(digest) - 1) // len(digest)
    return (digest * repeats)[:CHUNK_SIZE]


def write_entry(
    archive: ZipFile,
    archive_path: str,
    label: str,
    index: int,
    size_bytes: int,
) -> str:
    digest = hashlib.sha256()
    remaining = size_bytes
    chunk_index = 0

    with archive.open(archive_path, mode="w", force_zip64=True) as output:
        while remaining > 0:
            block = deterministic_chunk(label, index, chunk_index)
            block = block[: min(len(block), remaining)]
            output.write(block)
            digest.update(block)
            remaining -= len(block)
            chunk_index += 1

    return digest.hexdigest()


def archive_sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        while block := source.read(CHUNK_SIZE):
            digest.update(block)
    return digest.hexdigest()


def create_fixture(
    output_path: Path,
    label: str,
    file_count: int,
    file_size: int,
    relative_directory: str,
) -> dict[str, object]:
    samples: list[dict[str, object]] = []

    with ZipFile(output_path, mode="w", compression=ZIP_STORED, allowZip64=True) as archive:
        for index in range(file_count):
            archive_path = (
                f"Data/DMLBenchmark/{relative_directory}/"
                f"{label}_{index:05d}.bin"
            )
            file_hash = write_entry(
                archive=archive,
                archive_path=archive_path,
                label=label,
                index=index,
                size_bytes=file_size,
            )
            if index < 5:
                samples.append(
                    {
                        "path": archive_path,
                        "size_bytes": file_size,
                        "sha256": file_hash,
                    }
                )

    return {
        "archive": output_path.name,
        "archive_sha256": archive_sha256(output_path),
        "file_count": file_count,
        "file_size_bytes": file_size,
        "uncompressed_bytes": file_count * file_size,
        "sample_entries": samples,
    }


def main() -> None:
    args = parse_args()
    validate_positive("--small-file-count", args.small_file_count)
    validate_positive("--small-file-bytes", args.small_file_bytes)
    validate_positive("--large-file-count", args.large_file_count)
    validate_positive("--large-file-bytes", args.large_file_bytes)

    output_dir = args.output_dir.expanduser().resolve()
    output_dir.mkdir(parents=True, exist_ok=True)

    small_path = output_dir / "dml-benchmark-small-files.zip"
    large_path = output_dir / "dml-benchmark-large-files.zip"

    manifest = {
        "format_version": 1,
        "zip_compression": "stored",
        "fixtures": [
            create_fixture(
                output_path=small_path,
                label="small",
                file_count=args.small_file_count,
                file_size=args.small_file_bytes,
                relative_directory="SmallFiles",
            ),
            create_fixture(
                output_path=large_path,
                label="large",
                file_count=args.large_file_count,
                file_size=args.large_file_bytes,
                relative_directory="LargeFiles",
            ),
        ],
    }

    manifest_path = output_dir / "dml-benchmark-manifest.json"
    manifest_path.write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")

    print(f"Created: {small_path}")
    print(f"Created: {large_path}")
    print(f"Created: {manifest_path}")


if __name__ == "__main__":
    main()
