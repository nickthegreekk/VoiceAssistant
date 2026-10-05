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
#   RUN_TIMEOUT_MIN  wall-clock limit per run (default: 25). NOT optional in practice: a run
#                    aborted mid-flight by e.g. `adb shell am force-stop` leaves gradle
#                    waiting on an instrumentation that will never report, with no result
#                    XML written, forever. Observed on this repo: 9+ minutes of silence.

set -uo pipefail

N="${1:-}"
if ! [[ "$N" =~ ^[0-9]+$ ]] || [[ "$N" -lt 1 ]]; then
    echo "usage: $0 <number-of-runs>" >&2
    exit 2
fi

RUN_TIMEOUT_MIN="${RUN_TIMEOUT_MIN:-25}"

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

# ---------------------------------------------------------------------------
# Memory sampler.
#
# Why it runs during the run and not at the start/end of it: the gradle task
# UNINSTALLS the app when it finishes, so a meminfo taken after the run finds
# nothing installed, and one taken before it measures a cold system that says
# nothing about what 244 tests did to the heap. The interesting number is the
# trend *while* the suite walks its classes.
#
# dumpsys meminfo <pkg> is ~100-300ms, so a 5s tick is cheap and dense enough
# to show a leak as a staircase. Samples go to a TSV (machine-readable, for
# diffing) plus the full dumps for the first and last successful sample of
# each package (so the breakdown is available without re-running).
# ---------------------------------------------------------------------------
MEM_PKGS="com.nikosm.voiceassistant com.nikosm.voiceassistant.test"
MEM_TICK="${MEM_TICK:-5}"
MEM_PID=""

start_sampler() {
    local tag="$1"
    MEM_TSV="$OUT/${tag}-meminfo.tsv"
    : > "$MEM_TSV"
    (
        while :; do
            local now
            now=$(date +%s)
            for pkg in $MEM_PKGS; do
                local dump
                dump=$(timeout 20 adb -s "$ANDROID_SERIAL" shell dumpsys meminfo "$pkg" 2>/dev/null | tr -d '\r')
                # An uninstalled/not-yet-installed package dumps an error, not a table.
                if ! printf '%s' "$dump" | grep -q 'TOTAL PSS'; then
                    continue
                fi
                printf '%s' "$dump" | awk -v ts="$now" -v pkg="$pkg" '
                    /^ *Java Heap:/            { jh = $3 }
                    /^ *Native Heap:/          { nh = $3 }
                    /^ *Graphics:/             { gr = $2 }
                    # On this device "TOTAL PSS:  N  TOTAL RSS:  N  TOTAL SWAP PSS:  N"
                    # is ONE line, so RSS is field 6 and swap field 10 -- not field 3.
                    # (Some builds split them onto their own lines, hence the fallbacks.)
                    /^ *TOTAL PSS:/ {
                        tp = $3
                        if ($4 == "TOTAL" && $5 == "RSS:") tr = $6
                        if ($7 == "TOTAL" && $8 == "SWAP" && $9 == "PSS:") sw = $10
                    }
                    /^ *TOTAL RSS:/            { tr = $3 }
                    /^ *TOTAL SWAP PSS:/       { sw = $3 }
                    END { printf "%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n", ts, pkg, tp, tr, jh, nh, sw, gr }
                ' >> "$MEM_TSV"
            done
            sleep "$MEM_TICK"
        done
    ) &
    MEM_PID=$!
}

stop_sampler() {
    local tag="$1"
    [[ -n "$MEM_PID" ]] || return 0
    kill "$MEM_PID" 2>/dev/null
    wait "$MEM_PID" 2>/dev/null
    MEM_PID=""
    if [[ -s "$MEM_TSV" ]]; then
        echo "$tag meminfo samples: $(wc -l < "$MEM_TSV")"
    else
        echo "$tag meminfo samples: NONE (package never observed installed)"
    fi
}

for ((run = 1; run <= N; run++)); do
    LOG="$OUT/run${run}.log"
    echo ""
    echo "===== run $run/$N -> $LOG ====="

    # --rerun-tasks: without it gradle calls a second identical run UP-TO-DATE and never
    # executes the tests, which would make N runs into one run and N-1 no-ops.
    # timeout(1) because an aborted run leaves gradle waiting on an instrumentation that
    # will never report; 124/137 is how it says so.
    start_sampler "run${run}"
    timeout --signal=TERM --kill-after=60s "${RUN_TIMEOUT_MIN}m" \
        "$GRADLEW" connectedDebugAndroidTest --rerun-tasks --continue \
        > "$LOG" 2>&1
    gradle_rc=$?
    # Stopped here, before any of the `continue`s below, so an aborted run still
    # gets its memory trace -- an abort is exactly when the trace matters most.
    stop_sampler "run${run}"
    if [[ $gradle_rc -eq 124 || $gradle_rc -eq 137 ]]; then
        echo "run $run: ABORTED -- no result after ${RUN_TIMEOUT_MIN}m, killed (rc=$gradle_rc)"
        echo "         log tail:"
        tail -n 15 "$LOG" | sed 's/^/         | /'
        SUMMARY+=("run $run: ABORTED (timeout after ${RUN_TIMEOUT_MIN}m)")
        overall=1
        continue
    fi

    # The XML is keyed by device name, which contains spaces; find it rather than guess.
    XML=""
    for candidate in "$REPO_ROOT/$RESULTS_REL"/*.xml; do
        [[ -f "$candidate" ]] && XML="$candidate"
    done
    if [[ -n "$XML" ]]; then
        cp "$XML" "$OUT/run${run}.xml"
    fi

    # The summary XML says WHICH test died but never WHY: an aborted instrumentation writes
    # an empty <failure/> with no trace. The per-test logcat next to it is the only place the
    # crash actually shows up, so archive it with the run. (Learned the hard way: a run that
    # stopped dead at a screenshot test gave a bare empty <failure/> and nothing else.)
    for devdir in "$REPO_ROOT/$RESULTS_REL"/*/; do
        [[ -d "$devdir" ]] || continue
        if compgen -G "$devdir*.txt" > /dev/null; then
            mkdir -p "$OUT/run${run}-logcat"
            cp "$devdir"*.txt "$OUT/run${run}-logcat/" 2>/dev/null
        fi
    done

    if [[ -z "$XML" ]]; then
        echo "run $run: NO RESULT XML -- the run died before writing results (gradle rc=$gradle_rc)"
        echo "         log tail:"
        tail -n 15 "$LOG" | sed 's/^/         | /'
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

# Zero tests means the run never executed -- an aborted instrumentation still writes a
# well-formed XML, and "0 failed" off the back of it is the single most misleading line
# this script could print. Treat it as a failure, not a pass.
if tests == 0:
    print("")
    print("  ABORTED: the run executed 0 tests. An XML was written, but nothing ran in it,")
    print("  so this is NOT a pass -- check the log for a killed or restarted instrumentation.")
    print("")
    print("  CLASS RUN ORDER for this run:")
    for i, name in enumerate(order, 1):
        print("   %2d. %s" % (i, name))
    sys.exit(1)

if bad:
    print("")
    print("  FAILING TESTS (in the order they ran):")
    for cls, name, kind, msg, body in bad:
        empty = not msg and not body
        print("   * [%s] %s.%s" % (kind.upper(), cls, name))
        if empty:
            # An empty <failure/> with no message and no body is what a KILLED test looks
            # like, not an assertion that failed. Saying "(no message)" would send the
            # reader hunting for an assertion that does not exist.
            print("       assertion: (none recorded -- the <failure> element is empty, which")
            print("                  means this test was killed or aborted mid-run rather")
            print("                  than failing an assertion)")
        else:
            for line in (msg or "(no message)").splitlines():
                print("       assertion: %s" % line)
        # First few frames of the trace, enough to locate the assertion.
        frames = [l for l in body.splitlines() if l.strip().startswith("at ")]
        for line in frames[:6]:
            print("       %s" % line.strip())
    print("")
    print("  A run that stops early is a crash, not an assertion: compare the class count")
    print("  below against a healthy run, and read run<N>-logcat/ for the crash trace.")
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
    # A nonzero gradle rc with a parsable XML still needs its tail: the XML cannot explain
    # an install failure, a crashed device, or a task that never ran.
    if [[ $gradle_rc -ne 0 && $report_rc -eq 0 ]]; then
        echo "  note: gradle exited $gradle_rc though the XML reported no failures. Log tail:"
        tail -n 15 "$LOG" | sed 's/^/         | /'
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
