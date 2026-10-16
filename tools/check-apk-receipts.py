#!/usr/bin/env python3
"""Check build receipts against their APKs and AGP output metadata (no SDK needed)."""

import hashlib
import json
from pathlib import Path
import re
import sys
from zipfile import ZipFile


def check(apk: Path) -> None:
    receipt = json.loads(apk.with_name(apk.name + ".receipt.json").read_text())
    assert receipt["schemaVersion"] == 1, "Unsupported receipt schema"
    with apk.open("rb") as stream:
        digest = hashlib.file_digest(stream, "sha256").hexdigest()
    assert receipt["apk"] == {
        "fileName": apk.name,
        "sizeBytes": apk.stat().st_size,
        "sha256": digest,
    }, "APK identity mismatch"
    with ZipFile(apk) as archive:
        provenance = json.loads(archive.read("assets/dml-build-provenance.json"))
    assert receipt["build"] == provenance, "Receipt differs from embedded provenance"
    assert provenance["schemaVersion"] == 1, "Unsupported provenance schema"
    assert re.fullmatch(r"[0-9a-f]{40}", provenance["commit"]), "Invalid commit"
    assert isinstance(provenance["dirty"], bool), "Invalid dirty flag"
    for field in ("trackedDiffSha256", "untrackedFilesSha256"):
        assert re.fullmatch(r"[0-9a-f]{64}", provenance[field]), f"Invalid {field}"
    metadata = json.loads((apk.parent / "output-metadata.json").read_text())
    output = next(item for item in metadata["elements"] if item["outputFile"] == apk.name)
    assert provenance["applicationId"] == metadata["applicationId"], "Package mismatch"
    assert provenance["variant"] == metadata["variantName"], "Variant mismatch"
    assert provenance["versionName"] == output["versionName"], "Version name mismatch"
    assert provenance["versionCode"] == output["versionCode"], "Version code mismatch"
    signing = receipt["signing"]
    assert signing["status"] in ("verified", "unsigned"), "Invalid signing status"
    assert bool(signing["certificateSha256"]) == (signing["status"] == "verified"), "Invalid signers"
    for digest in signing["certificateSha256"]:
        assert re.fullmatch(r"[0-9a-f]{64}", digest), "Invalid certificate digest"
    print(f"Receipt verified: {apk}")


if __name__ == "__main__":
    if len(sys.argv) < 2:
        sys.exit("Usage: python3 tools/check-apk-receipts.py APK [APK ...]")
    for argument in sys.argv[1:]:
        check(Path(argument))
