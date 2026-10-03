#!/usr/bin/env bash
#
# Device display hygiene for screenshot / layout work.
#
# Android remembers display overrides across reboots in `settings get system`
# (display_density_forced, font_scale), so a "temporary" wm density can silently
# outlive the session that set it and change what a Compose test sees. This script
# records the device's real values before anything touches them and puts them back
# afterwards -- including when the command in between fails.
#
#   tools/device-display.sh save                     # record current values
#   tools/device-display.sh restore                  # put the recorded values back
#   tools/device-display.sh show                     # current + recorded values
#   tools/device-display.sh scaled 360 -- ./gradlew connectedDebugAndroidTest
#                                                     # run at an override, always restore
#
# "Physical" means: what the hardware reports once overrides are cleared.

set -euo pipefail

ADB="${ADB:-adb}"
SERIAL="${ANDROID_SERIAL:-}"
if [[ -n "$SERIAL" ]]; then
    adb() { command "$ADB" -s "$SERIAL" "$@"; }
fi

STATE_FILE="${DEVICE_DISPLAY_STATE:-${TMPDIR:-/tmp}/voiceassistant-device-display.state}"

adbd() { adb shell "$@" 2>/dev/null | tr -d '\r'; }

# --- reading -----------------------------------------------------------------

# "440" -> forced override density, "physical" -> none.
current_density_override() {
    local out
    out="$(adbd wm density)"
    if grep -q 'Override density' <<<"$out"; then
        grep -o 'Override density: [0-9]*' <<<"$out" | grep -o '[0-9]*$'
    else
        echo "physical"
    fi
}

current_density_physical() {
    adbd wm density | grep -o 'Physical density: [0-9]*' | grep -o '[0-9]*$'
}

current_size_override() {
    local out
    out="$(adbd wm size)"
    if grep -q 'Override size' <<<"$out"; then
        grep -o 'Override size: [0-9]*x[0-9]*' <<<"$out" | grep -o '[0-9]*x[0-9]*$'
    else
        echo "physical"
    fi
}

current_size_physical() {
    adbd wm size | grep -o 'Physical size: [0-9]*x[0-9]*' | grep -o '[0-9]*x[0-9]*$'
}

current_font_scale() {
    adbd settings get system font_scale | grep -o '[0-9.]*' || echo "1.0"
}

# --- writing -----------------------------------------------------------------

clear_overrides() {
    adbd wm density reset >/dev/null || true
    adbd wm size reset >/dev/null || true
    # The persistent twins of the same overrides; empty means "follow hardware".
    adbd settings delete system display_density_forced >/dev/null 2>&1 || true
    adbd settings put system font_scale 1.0 >/dev/null || true
}

save() {
    {
        echo "density_physical=$(current_density_physical)"
        echo "density_override=$(current_density_override)"
        echo "size_physical=$(current_size_physical)"
        echo "size_override=$(current_size_override)"
        echo "font_scale=$(current_font_scale)"
    } >"$STATE_FILE"
    echo "recorded device display state -> $STATE_FILE"
    cat "$STATE_FILE"
}

field() { grep "^$1=" "$STATE_FILE" 2>/dev/null | cut -d= -f2- || true; }

restore() {
    if [[ ! -f "$STATE_FILE" ]]; then
        echo "no recorded state ($STATE_FILE); leaving the device alone"
        return 0
    fi
    local d_override s_override font
    d_override="$(field density_override)"
    s_override="$(field size_override)"
    font="$(field font_scale)"

    # Always start from hardware defaults: `wm density reset` does NOT clear
    # display_density_forced, so a stale forced value survives the reset.
    clear_overrides
    [[ "$d_override" != "physical" ]] && adbd wm density "$d_override" >/dev/null || true
    [[ "$s_override" != "physical" ]] && adbd wm size "$s_override" >/dev/null || true
    adbd settings put system font_scale "${font:-1.0}" >/dev/null || true

    # `wm density reset` returns before the window manager has actually applied the
    # new config, so reading straight afterwards can still show the old override.
    # Wait for the reported values to settle, otherwise `show` below prints a lie.
    local i
    for i in $(seq 1 20); do
        [[ "$(current_density_override)" == "$d_override" \
            && "$(current_size_override)" == "$s_override" ]] && break
        sleep 0.5
    done
    rm -f "$STATE_FILE"
    echo "restored device display state"
    show
}

show() {
    echo "device : $(adbd getprop ro.product.model | tr -d '\r')  serial=$(${ADB} ${SERIAL:+-s "$SERIAL"} get-serialno 2>/dev/null)"
    echo "size   : physical=$(current_size_physical) override=$(current_size_override)"
    echo "density: physical=$(current_density_physical) override=$(current_density_override)"
    echo "font   : font_scale=$(current_font_scale) display_density_forced=$(adbd settings get system display_density_forced)"
    if [[ -f "$STATE_FILE" ]]; then
        echo "recorded: $(tr '\n' ' ' <"$STATE_FILE")"
    else
        echo "recorded: (nothing recorded)"
    fi
}

scaled() {
    local density="$1"; shift
    [[ "${1:-}" == "--" ]] && shift
    save
    # shellcheck disable=SC2064
    trap restore EXIT INT TERM
    adbd wm density "$density" >/dev/null
    echo "--- running at density override $density ---"
    "$@"
}

case "${1:-}" in
    save) save ;;
    restore) restore ;;
    show) show ;;
    scaled)
        # Drop the verb itself before the function reads its arguments.
        shift
        scaled "$@"
        ;;
    *)
        echo "usage: $0 {save|restore|show|scaled <density> -- <command...>}" >&2
        exit 2
        ;;
esac