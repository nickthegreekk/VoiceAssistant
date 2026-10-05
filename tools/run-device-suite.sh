#!/usr/bin/env bash
#
# Run the connected-device suite N times, keeping every run's full log and result XML.
#
#   tools/run-device-suite.sh 3
#
# Why this exists: `connectedAndroidTest` overwrites the XML report on every invocation and
# prints only a summary line, so a loop built from a bare `./gradlew` loses the failing
# test the moment the next run starts. That is how a real failure in this repo went
# unrecorded. This script never truncates, archives per run, and reports the failing test,
# its assertion and the class order from that run's own XML.
#
# Exits nonzero if ANY run failed, so a caller cannot mistake a partial pass for a clean one.
#
# Env:
#   ANDROID_SERIAL   required when more than one device is attached; exported to gradle so
#                    every run targets the same device
#   DEVICE_SUITE_OUT where archives go (default: $TMPDIR/device-suite-<UTC stamp>)

set -uo pipefail

N="${1:-}"
if ! [[ "$N" =~ ^[0-9]+$ ]] || [[ "$N" -lt 1 ]]; then
    echo "usage: $0 <number-of-runs>" >&2
    exit 2
fi

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

GRADLEW="$REPO_ROOT/gradlew"
RESULTS_REL="app/build/outputs/androidTest-results/connected/debug"
STAMP="$(date -u +%Y%m%dT%H%M%SZ)"
OUT="${DEVICE_SUITE_OUT:-${TMPDIR:-/tmp}/device-suite-$STAMP}"
mkdir -p "$OUT"

# Pin the device once, before anything runs, so every run in the loop is comparable.
if [[ -z "${ANDROID_SERIAL:-}" ]]; then
    mapfile -t devices < <(adb devices | awk 'NR>1 && $2=="device" {print $1}')
    if [[ "${#devices[@]}" -eq 0 ]]; then
        echo "no device attached" >&2
        exit 2
    elif [[ "${#devices[@]}" -gt 1 ]]; then
        echo "several devices attached; set ANDROID_SERIAL to pick one:" >&2
        printf '  %s\n' "${devices[@]}" >&2
        exit 2
    fi
    export ANDROID_SERIAL="${devices[0]}"
fi
echo "device : $ANDROID_SERIAL ($(adb -s "$ANDROID_SERIAL" shell getprop ro.product.model 2>/dev/null | tr -d '\r'))"
echo "archive: $OUT"

overall=0
declare -a SUMMARY=()

for ((run = 1; run <= N; run++)); do
    LOG="$OUT/run${run}.log"
    echo ""
    echo "===== run $run/$N -> $LOG ====="

    # --rerun-tasks: without it gradle calls a second identical run UP-TO-DATE and never
    # executes the tests, which would make N runs into one run and N-1 no-ops.
    "$GRADLEW" connectedDebugAndroidTest --rerun-tasks --continue \
        > "$LOG" 2>&1
    gradle_rc=$?

    # The XML is keyed by device name, which contains spaces; find it rather than guess.
    XML=""
    for candidate in "$REPO_ROOT/$RESULTS_REL"/*.xml; do
        [[ -f "$candidate" ]] && XML="$candidate"
    done
    if [[ -n "$XML" ]]; then
        cp "$XML" "$OUT/run${run}.xml"
    fi

    if [[ -z "$XML" ]]; then
        echo "run $run: NO RESULT XML -- the run died before writing results (gradle rc=$gradle_rc)"
        echo "         see $LOG"
        SUMMARY+=("run $run: NO XML (gradle rc=$gradle_rc)")
        overall=1
        continue
    fi

    # Report straight out of the archived copy, not the live one, so what is printed here
    # is exactly what was kept.
    python3 - "$OUT/run${run}.xml" "$run" <<'PY'
import re, sys, xml.etree.ElementTree as ET

path, run = sys.argv[1], sys.argv[2]
try:
    root = ET.parse(path).getroot()
except Exception as exc:                       # unreadable XML is itself a failure
    print("run %s: cannot parse %s: %s" % (run, path, exc))
    sys.exit(2)

suites = ([root] if root.tag == "testsuite" else root.findall("testsuite"))
tests = failures = errors = skipped = 0
order, bad = [], []
for s in suites:
    name = s.get("name", "?").rsplit(".", 1)[-1]
    order.append(name)
    for tc in s.findall("testcase"):
        for kind in ("failure", "error"):
            node = tc.find(kind)
            if node is None:
                continue
            msg = (node.get("message") or "").strip()
            body = (node.text or "").strip()
            bad.append((name, tc.get("name", "?"), kind, msg, body))

for s in suites:
    tests += int(s.get("tests", 0))
    skipped += int(s.get("skipped", 0))
    failures += int(s.get("failures", 0))
    errors += int(s.get("errors", 0))

print("run %s: %d tests, %d failed, %d errors, %d skipped, %d classes"
      % (run, tests, failures, errors, skipped, len(order)))

if bad:
    print("")
    print("  FAILING TESTS (in the order they ran):")
    for cls, name, kind, msg, body in bad:
        print("   * [%s] %s.%s" % (kind.upper(), cls, name))
        for line in (msg or "(no message)").splitlines():
            print("       assertion: %s" % line)
        # First few frames of the trace, enough to locate the assertion.
        frames = [l for l in body.splitlines() if l.strip().startswith("at ")]
        for line in frames[:6]:
            print("       %s" % line.strip())
    print("")
    print("  CLASS RUN ORDER for this run:")
    for i, name in enumerate(order, 1):
        print("   %2d. %s" % (i, name))
sys.exit(1 if bad else 0)
PY
    report_rc=$?

    if [[ $report_rc -ne 0 || $gradle_rc -ne 0 ]]; then
        SUMMARY+=("run $run: FAILED")
        overall=1
    else
        SUMMARY+=("run $run: pass")
    fi
done

echo ""
echo "===== summary ====="
for line in "${SUMMARY[@]}"; do echo "  $line"; done
echo "  archive: $OUT"

if [[ $overall -ne 0 ]]; then
    echo "RESULT: FAILED (at least one run did not pass)"
    exit 1
fi
echo "RESULT: all $N runs passed"
