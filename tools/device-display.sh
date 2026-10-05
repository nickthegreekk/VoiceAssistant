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
#
# ## Why runtime permissions and app-ops are in here too
#
# A screenshot session that has to get past a permission dialog runs `pm grant`, and any
# manual app-op fiddling (`cmd appops set ...`) leaves a mode behind. Both outlive the
# session and are asserted on by the instrumentation suite: a leftover READ_PHONE_STATE mode
# of `ignore` makes PhoneCallGuardInstrumentedTest fail, with an assertion that has nothing
# to do with the code under test. So `save`/`restore` cover them alongside the display, and
# `restore` puts every permission and app-op back exactly as it was found.

set -euo pipefail

ADB="${ADB:-adb}"
SERIAL="${ANDROID_SERIAL:-}"
if [[ -n "$SERIAL" ]]; then
    adb() { command "$ADB" -s "$SERIAL" "$@"; }
fi

STATE_FILE="${DEVICE_DISPLAY_STATE:-${TMPDIR:-/tmp}/voiceassistant-device-display.state}"

# The package whose permissions and app-ops this script guards. Overridable so the script is
# reusable, but it defaults to the app under test.
PKG="${DEVICE_DISPLAY_PKG:-com.nikosm.voiceassistant}"

adbd() { adb shell "$@" 2>/dev/null | tr -d '\r'; }

# True once the package is actually installed: before the first install there is no UID, so
# `cmd appops get` errors and every read below would record noise.
package_installed() {
    adb shell pm list packages "$PKG" 2>/dev/null | grep -q "^package:$PKG$"
}


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

current_permissions() {
    adbd dumpsys package "$PKG" \
        | grep -oE 'android\.permission\.[A-Z_]+: granted=(true|false)' \
        | sed 's/^android\.permission\.//; s/: granted=/=/'
}

current_appops() {
    package_installed || return 0
    adbd cmd appops get "$PKG" | grep -oE '^[A-Z_]+: [a-z_]+' | tr -d ' ' | tr ':' '='
}

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
        # Kept in sibling files so a failure while applying one group cannot abandon the other.
        current_permissions > "${STATE_FILE}.perms" || true
        current_appops > "${STATE_FILE}.appops" || true
    } >"$STATE_FILE"
    echo "recorded device display state -> $STATE_FILE"
    cat "$STATE_FILE"
    echo "perms  : $(wc -l < "${STATE_FILE}.perms" 2>/dev/null || echo 0) recorded"
    echo "app-ops: $(tr '\n' ' ' < "${STATE_FILE}.appops" 2>/dev/null || true)"
}

field() { grep "^$1=" "$STATE_FILE" 2>/dev/null | cut -d= -f2- || true; }

# Put every recorded permission and app-op back. Best-effort per line: one that the OS
# refuses (a permission it will not let a script flip) must not abandon the rest.
restore_permissions() {
    local perms="${STATE_FILE}.perms" ops="${STATE_FILE}.appops" name value
    # NOTE the `</dev/null` on every adb call below. `adb` inherits this loop's stdin — the
    # redirected state file — and reads whatever is left of it, so without that redirect the
    # FIRST line is acted on and every later one is swallowed. That fails silently: the loop
    # just ends early, and the caller sees a restore that reported success.
    if [[ -s "$perms" ]]; then
        while IFS='=' read -r name value; do
            [[ -n "$name" ]] || continue
            if [[ "$value" == "true" ]]; then
                adbd pm grant "$PKG" "android.permission.$name" >/dev/null 2>&1 </dev/null || true
            else
                adbd pm revoke "$PKG" "android.permission.$name" >/dev/null 2>&1 </dev/null || true
            fi
        done < "$perms"
    fi
    if [[ -s "$ops" ]] && package_installed; then
        while IFS='=' read -r name value; do
            [[ -n "$name" ]] || continue
            adbd cmd appops set "$PKG" "$name" "$value" >/dev/null 2>&1 </dev/null || true
        done < "$ops"
    fi
    rm -f "$perms" "$ops"
}

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
    # Display first, then permissions and app-ops: a `pm revoke` restarts nothing, but a
    # failing one must not stop the app-ops from being put back.
    restore_permissions

    rm -f "$STATE_FILE"
    echo "restored device display, permission and app-op state"
    show
}

show() {
    echo "device : $(adbd getprop ro.product.model | tr -d '\r')  serial=$(${ADB} ${SERIAL:+-s "$SERIAL"} get-serialno 2>/dev/null)"
    echo "size   : physical=$(current_size_physical) override=$(current_size_override)"
    echo "density: physical=$(current_density_physical) override=$(current_density_override)"
    echo "font   : font_scale=$(current_font_scale) display_density_forced=$(adbd settings get system display_density_forced)"
    if package_installed; then
        echo "perms  : $(current_permissions | grep -c '=true' || true) granted, $(current_permissions | grep -c '=false' || true) not"
        echo "app-ops: $(current_appops | tr '\n' ' ')"
    fi
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