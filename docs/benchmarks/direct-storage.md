# Direct Storage Deployment Benchmark

## Purpose

Measure the deployment effect of replacing the released Storage Access Framework
backend with the direct-filesystem backend. This is a same-device, same-fixture,
same-target comparison. It is not a general storage benchmark.

## Compared builds

Record the exact commit and APK for both sides:

- **SAF baseline:** the released `v0.6.0-beta` implementation or another named
  commit that still deploys through the selected tree URI.
- **Direct build:** the reviewed direct-storage migration commit.

Do not compare different devices, Android versions, storage locations, profiles,
or fixture contents.

## Create deterministic fixtures

On the development machine:

```bash
python3 tools/create-deployment-benchmark-fixtures.py \
  --output-dir "$HOME/Downloads/dml-deployment-benchmark"
```

The default fixtures are:

- 2,000 files of 4 KiB each, representing metadata-heavy loose-file deployment;
- 4 files of 32 MiB each, representing throughput-heavy deployment.

The tool creates ZIP files with uncompressed entries plus a JSON manifest with
archive hashes. Copy the complete output directory to the same shared-storage
location used by both builds.

## Device preparation

1. Use one Android device and one storage location for the entire comparison.
2. Disable battery saver and keep the device at a stable temperature.
3. Close other storage-heavy applications.
4. Use a disposable game/profile target with enough free space.
5. Install each fixture as its own mod and enable only the fixture being measured.
6. Confirm the target is empty before the first deploy and that each timed run
   rewrites the full fixture rather than producing a no-op plan.
7. Keep the profile, fixture, target folder, and DML settings identical between
   builds.

## Timing method

DML's operation log records an `OPERATION END` duration in milliseconds. For each
build and fixture:

1. Run one untimed warm-up full redeploy.
2. Run **Force full redeploy** five times.
3. Record the five operation durations from DML's log.
4. Confirm every run reports success and the expected final file count.
5. Discard and repeat a run if Android interrupts it, the plan is a no-op, or
   thermal throttling is apparent.

The benchmark intentionally uses full redeploy so every measured run performs the
same file-copy workload.

## Summarize results

Example:

```bash
python3 tools/summarize-deployment-benchmark.py \
  --workload "2,000 x 4 KiB files" \
  --saf-ms "12500,12100,12350,12200,12400" \
  --direct-ms "4300,4200,4250,4180,4270"
```

Run the summarizer separately for the small-file and large-file fixtures. Record
all raw samples, not only the fastest run.

## Result template

| Field | SAF baseline | Direct build |
|---|---:|---:|
| Commit |  |  |
| APK/version |  |  |
| Android device |  | Same device |
| Android version |  | Same version |
| Target path |  | Same path |
| Fixture SHA-256 |  | Same hash |
| Warm-up result |  |  |
| Run 1 (ms) |  |  |
| Run 2 (ms) |  |  |
| Run 3 (ms) |  |  |
| Run 4 (ms) |  |  |
| Run 5 (ms) |  |  |
| Median (ms) |  |  |
| Mean (ms) |  |  |
| Verification result |  |  |

## Acceptance rule

Do not claim a performance improvement until both workloads are measured on the
same device and the raw results are committed or attached to the relevant task.
Correctness remains mandatory even if direct deployment is faster: file counts,
content hashes where practical, profile isolation, rollback behavior, and plugin
ordering must still pass.

## Recorded exploratory result — 2026-06-22

The first same-device capture used:

- **Device:** AYN Thor
- **Android:** 13 / API 33
- **SAF baseline:** `3480a146dc3651d9a5de270c108a35d3aa8e2764`
- **Direct build:** `80dc51522489abd8631e52d52370a96ff0986b62`
- **Small-file fixture:** 2,000 files × 4 KiB
- **Large-file fixture:** 4 files × 32 MiB

### Raw operation durations

| Workload | SAF baseline | Direct build |
|---|---|---|
| Small files | 30,247; 109,180; 108,495 ms | 8,429; 14,098; 13,986 ms |
| Large files | 474; 466; 493; 491 ms | 45,704; 345; 317; 319 ms |

The second and third small-file samples were close but not duplicates. Using
those internally stable later samples gives approximately 108.8 seconds for SAF
and 14.0 seconds for direct paths, an exploratory speedup of about 7.75× and
about 87% lower elapsed time.

The first direct large-file run was an obvious setup/cold-start outlier. Treating
the first run from each build as warm-up leaves a 491 ms SAF median and a 319 ms
direct median, an exploratory speedup of about 1.54× and about 35% lower
elapsed time.

### Interpretation

The capture strongly supports retaining the direct-filesystem architecture,
especially for high-file-count deployment. It does **not** meet the complete
five-measured-run protocol above: sample counts were limited, warm-up separation
was inconsistent, and one large direct run was a major outlier. Therefore:

- the result is suitable as engineering evidence for the storage decision;
- the raw numbers may be retained in project documentation;
- the result must be described as exploratory; and
- no definitive public speed multiplier should be advertised without a new,
  controlled run following the complete protocol.
