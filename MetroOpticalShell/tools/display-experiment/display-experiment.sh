#!/usr/bin/env bash
# Operator tool for the Tier 1 density experiment (TZ 4.3, OP01-D).
#
# This runs on a HOST machine over ADB. It is deliberately NOT part of the APK:
# an ordinary app holds no shell privileges, and TZ 13 forbids smuggling a
# root/hidden-API path into the shipped build.
#
# It records your original state before touching anything, derives candidate
# densities from YOUR device rather than a hard-coded number, and refuses to go
# below a readability guard unless you force it.

set -euo pipefail

GUARD_FRACTION="0.625"   # 62.5% of stock density; see DisplayMath.
STATE_DIR="${XDG_STATE_HOME:-$HOME/.local/state}/metro-optical-shell"
STATE_FILE="$STATE_DIR/display-baseline.env"

die() { printf 'error: %s\n' "$*" >&2; exit 1; }
note() { printf '%s\n' "$*" >&2; }

command -v adb >/dev/null || die "adb not found in PATH"

adb_one() {
  local count
  count="$(adb devices | awk 'NR>1 && $2=="device"' | wc -l | tr -d ' ')"
  [ "$count" = "1" ] || die "expected exactly one connected device, found $count"
}

read_state() {
  adb_one
  local raw physical override
  raw="$(adb shell wm density)"
  physical="$(printf '%s' "$raw" | sed -n 's/.*Physical density: *\([0-9]\+\).*/\1/p' | head -1)"
  override="$(printf '%s' "$raw" | sed -n 's/.*Override density: *\([0-9]\+\).*/\1/p' | head -1)"
  [ -n "$physical" ] || die "could not parse 'wm density' output: $raw"
  PHYSICAL_DPI="$physical"
  OVERRIDE_DPI="$override"
  if [ -n "$override" ]; then
    CURRENT_DPI="$override"; HAD_OVERRIDE=1
  else
    CURRENT_DPI="$physical"; HAD_OVERRIDE=0
  fi

  local size
  size="$(adb shell wm size)"
  WIDTH_PX="$(printf '%s' "$size" | sed -n 's/.*: *\([0-9]\+\)x[0-9]\+.*/\1/p' | tail -1)"
  HEIGHT_PX="$(printf '%s' "$size" | sed -n 's/.*: *[0-9]\+x\([0-9]\+\).*/\1/p' | tail -1)"
  [ -n "$WIDTH_PX" ] || die "could not parse 'wm size' output: $size"
}

cmd_status() {
  read_state
  local dp guard
  dp=$(( WIDTH_PX * 160 / CURRENT_DPI ))
  guard="$(awk -v d="$PHYSICAL_DPI" -v f="$GUARD_FRACTION" 'BEGIN{printf "%d", d*f+0.5}')"
  cat <<EOF
device         : $(adb shell getprop ro.product.manufacturer | tr -d '\r') $(adb shell getprop ro.product.model | tr -d '\r')
android        : $(adb shell getprop ro.build.version.release | tr -d '\r') (API $(adb shell getprop ro.build.version.sdk | tr -d '\r'))
panel          : ${WIDTH_PX}x${HEIGHT_PX} px
physical dpi   : ${PHYSICAL_DPI}
override dpi   : ${OVERRIDE_DPI:-<none>}
effective dpi  : ${CURRENT_DPI}
width dp       : ${dp}
guard dpi      : ${guard}  (${GUARD_FRACTION} of physical)
max safe dp    : $(( WIDTH_PX * 160 / guard ))
dpi for 600dp  : $(( WIDTH_PX * 160 / 600 ))
dpi for 840dp  : $(( WIDTH_PX * 160 / 840 ))
EOF
  if [ "$(( WIDTH_PX * 160 / guard ))" -lt 600 ]; then
    note ""
    note "VERDICT: this panel cannot reach 600dp within the readability guard."
    note "The honest answer for this device is that no useful tablet width exists."
  fi
}

cmd_save() {
  read_state
  mkdir -p "$STATE_DIR"
  cat > "$STATE_FILE" <<EOF
# Metro Optical Shell display baseline. Written $(date -Iseconds).
PHYSICAL_DPI=$PHYSICAL_DPI
ORIGINAL_DPI=$CURRENT_DPI
HAD_OVERRIDE=$HAD_OVERRIDE
WIDTH_PX=$WIDTH_PX
HEIGHT_PX=$HEIGHT_PX
EOF
  note "baseline saved to $STATE_FILE"
  cat "$STATE_FILE"
}

cmd_apply() {
  local target="${1:-}" force="${2:-}"
  [ -n "$target" ] || die "usage: $0 apply <dpi> [--force]"
  read_state

  [ -f "$STATE_FILE" ] || { note "no baseline yet; saving one first"; cmd_save; }

  local guard
  guard="$(awk -v d="$PHYSICAL_DPI" -v f="$GUARD_FRACTION" 'BEGIN{printf "%d", d*f+0.5}')"
  if [ "$target" -lt "$guard" ] && [ "$force" != "--force" ]; then
    die "${target}dpi is below the readability guard of ${guard}dpi. Re-run with --force if you mean it."
  fi

  local new_dp=$(( WIDTH_PX * 160 / target ))
  note "This changes the density for the WHOLE system, not just the shell."
  note "  ${CURRENT_DPI}dpi -> ${target}dpi"
  note "  width $(( WIDTH_PX * 160 / CURRENT_DPI ))dp -> ${new_dp}dp"
  note "  everything becomes ~$(awk -v a="$target" -v b="$CURRENT_DPI" 'BEGIN{printf "%d", a*100/b}')% of its current physical size"
  printf 'Type APPLY to continue: ' >&2
  local answer; read -r answer
  [ "$answer" = "APPLY" ] || die "aborted; nothing changed"

  adb shell wm density "$target"
  sleep 2

  # Verify rather than assume (TZ 5.2 OBSERVED vs REQUESTED).
  read_state
  if [ "$CURRENT_DPI" = "$target" ]; then
    note "OBSERVED: density is now ${CURRENT_DPI}dpi, width $(( WIDTH_PX * 160 / CURRENT_DPI ))dp"
  else
    note "NOT OBSERVED: asked for ${target}dpi but the device reports ${CURRENT_DPI}dpi."
    note "The ROM may have overridden or reverted it. Treat this as a failure."
    exit 1
  fi
}

cmd_restore() {
  [ -f "$STATE_FILE" ] || die "no baseline at $STATE_FILE; restore by hand per docs/DISPLAY_ROLLBACK.md"
  # shellcheck disable=SC1090
  . "$STATE_FILE"
  adb_one
  if [ "${HAD_OVERRIDE:-0}" = "1" ]; then
    note "restoring your previous override: ${ORIGINAL_DPI}dpi"
    adb shell wm density "$ORIGINAL_DPI"
  else
    note "you had no override; resetting rather than writing a number"
    adb shell wm density reset
  fi
  sleep 2
  read_state
  note "now: ${CURRENT_DPI}dpi, width $(( WIDTH_PX * 160 / CURRENT_DPI ))dp"
  if [ "${HAD_OVERRIDE:-0}" = "0" ] && [ -n "$OVERRIDE_DPI" ]; then
    note "WARNING: an override is still present. Check manually."
    exit 1
  fi
}

cmd_candidates() {
  read_state
  note "candidates derived from this device's ${PHYSICAL_DPI}dpi stock density:"
  for f in 95 90 85 80 75 70 65; do
    local dpi=$(( PHYSICAL_DPI * f / 100 ))
    [ "$dpi" -ge 72 ] || continue
    printf '  %4ddpi  -> %4ddp  (%d%% of stock size)\n' \
      "$dpi" "$(( WIDTH_PX * 160 / dpi ))" "$f"
  done
}

case "${1:-}" in
  status)     cmd_status ;;
  save)       cmd_save ;;
  candidates) cmd_candidates ;;
  apply)      shift; cmd_apply "$@" ;;
  restore)    cmd_restore ;;
  *)
    cat >&2 <<EOF
Metro Optical Shell — display experiment tool (host-side, over ADB)

  $0 status       show panel, density, width dp and the mathematical verdict
  $0 candidates   list safe candidate densities derived from this device
  $0 save         record the current state as the restore baseline
  $0 apply <dpi>  change system density (asks for confirmation, then verifies)
  $0 restore      put YOUR original configuration back

Read docs/DISPLAY_ROLLBACK.md before using apply.
EOF
    exit 2 ;;
esac
