#!/usr/bin/env bash
# verify-design-style.sh
# ----------------------------------------------------------------------------
# Lint-as-skill verifier for the spatial-ui-design-style skill.
# Enforces the four highest-priority rules (R1-R4), token-routing checks
# (R5-R7), and migrated D2C checklist heuristics (R8). See
# ../references/compliance-signals.md for the full spec.
#
# Usage:
#   verify-design-style.sh <module-or-src-path> [<more paths> ...]
#
# Exit codes:
#   0  no errors (warnings may exist)
#   1  one or more errors
#   2  invalid invocation
#
# Notes:
#   - Scope: *.kt files under src/main/{java,kotlin}/**.
#   - Generated output (build/, generated/, *.kts, res/) is automatically
#     skipped.
# ----------------------------------------------------------------------------
set -euo pipefail

if [[ $# -lt 1 ]]; then
  echo "usage: $0 <module-or-src-path> [<more paths> ...]" >&2
  exit 2
fi

# ---------- helpers ----------
ERRORS=0
WARNINGS=0

# Pick a grep flavor that supports -P (PCRE). Fall back to extended regex.
GREP_BIN="grep"
if echo "" | grep -P "" >/dev/null 2>&1; then
  GREP_FLAGS=("-P" "-n" "-r" "--include=*.kt")
else
  GREP_BIN="grep"
  GREP_FLAGS=("-E" "-n" "-r" "--include=*.kt")
fi

EXCLUDES=(
  "--exclude-dir=build"
  "--exclude-dir=generated"
  "--exclude-dir=.gradle"
  "--exclude-dir=.idea"
  "--exclude-dir=test"
  "--exclude-dir=androidTest"
  "--exclude-dir=res"
)

# scan <pattern> <severity:error|warning|info> <message> [paths...]
scan() {
  local pattern="$1"; shift
  local severity="$1"; shift
  local message="$1"; shift
  local matches
  matches=$("$GREP_BIN" "${GREP_FLAGS[@]}" "${EXCLUDES[@]}" "$pattern" "$@" 2>/dev/null || true)
  if [[ -n "$matches" ]]; then
    while IFS= read -r line; do
      [[ -z "$line" ]] && continue
      printf '[%s] %s :: %s\n' "$severity" "$message" "$line"
      case "$severity" in
        error) ERRORS=$((ERRORS+1));;
        warning) WARNINGS=$((WARNINGS+1));;
      esac
    done <<<"$matches"
  fi
}

# scan_without_fixed_figma_color <pattern> <severity> <message> [paths...]
# Same as scan(), but allows explicitly annotated non-root decorative colors
# extracted from Figma / screenshots. The annotation must be on the same line:
#   // design-style: fixed-figma-color <reason or token>
scan_without_fixed_figma_color() {
  local pattern="$1"; shift
  local severity="$1"; shift
  local message="$1"; shift
  local matches
  matches=$("$GREP_BIN" "${GREP_FLAGS[@]}" "${EXCLUDES[@]}" "$pattern" "$@" 2>/dev/null || true)
  if [[ -n "$matches" ]]; then
    while IFS= read -r line; do
      [[ -z "$line" ]] && continue
      if [[ "$line" == *"design-style: fixed-figma-color"* ]]; then
        continue
      fi
      printf '[%s] %s :: %s\n' "$severity" "$message" "$line"
      case "$severity" in
        error) ERRORS=$((ERRORS+1));;
        warning) WARNINGS=$((WARNINGS+1));;
      esac
    done <<<"$matches"
  fi
}

# require <pattern> <severity> <message> [paths...]
# Reports an error/warning if the pattern is NOT found at least once.
require() {
  local pattern="$1"; shift
  local severity="$1"; shift
  local message="$1"; shift
  if ! "$GREP_BIN" "${GREP_FLAGS[@]}" "${EXCLUDES[@]}" -l "$pattern" "$@" >/dev/null 2>&1; then
    printf '[%s] MISSING %s :: pattern=%s\n' "$severity" "$message" "$pattern"
    case "$severity" in
      error) ERRORS=$((ERRORS+1));;
      warning) WARNINGS=$((WARNINGS+1));;
    esac
  fi
}

# file_has_code_pattern <pattern> <file>
# Grep-like per-file predicate that ignores Kotlin line/block comments. This
# prevents commented-out imports or chained calls from satisfying mandatory
# design-style requirements.
file_has_code_pattern() {
  local pattern="$1"
  local file="$2"
  python3 - "$pattern" "$file" <<'PY'
import re
import sys

pattern, path = sys.argv[1], sys.argv[2]
try:
    text = open(path, encoding="utf-8").read()
except OSError:
    sys.exit(1)
text = re.sub(r"/\*.*?\*/", "", text, flags=re.S)
text = re.sub(r"^\s*//.*$", "", text, flags=re.M)
sys.exit(0 if re.search(pattern, text) else 1)
PY
}

# ---------- inputs ----------
PATHS=("$@")
echo "==> spatial-ui-design-style verifier"
echo "    paths: ${PATHS[*]}"
echo

# ---------- R1 — PicoTheme wrapping ----------
echo "-- R1 PicoTheme wrapping"
# Accept both `PicoTheme(` (with explicit args) and `PicoTheme {` (trailing
# lambda) — both are valid call sites for the wrapper.
require 'PicoTheme[ \t]*[\({]' error 'R1 PicoTheme wrapping is required' "${PATHS[@]}"
scan    'MaterialTheme\(' error 'R1 MaterialTheme leaked into app code; use PicoTheme' "${PATHS[@]}"
scan    'MaterialTheme\.(colorScheme|typography)' error 'R1 use PicoTheme.colorScheme / PicoTheme.typography' "${PATHS[@]}"

# ---------- R2 — built-in design components first (soft) ----------
echo "-- R2 built-in design preference (soft check)"
require 'import com\.pico\.spatial\.ui\.design\.' info 'R2 no design import found; ensure built-ins were considered' "${PATHS[@]}"
scan    '@Composable\s+fun\s+My[A-Z]\w*Button' warning 'R2 custom *Button — confirm built-in com.pico.spatial.ui.design.Button does not fit' "${PATHS[@]}"

# ---------- R3 — custom hover MUST use spatialHoverEffect ----------
echo "-- R3 hover effect"
scan 'Modifier\.hoverable\(' error 'R3 custom hover via hoverable() — use Modifier.spatialHoverEffect' "${PATHS[@]}"
# Hand-rolled hover scale: any animateFloatAsState near scale/scaleX/scaleY,
# combined with a hovered-state collector. Two narrower patterns instead of
# one wide regex (the wide regex almost never matched real code).
scan 'collectIsHoveredAsState\(' info 'R3 collectIsHoveredAsState detected — confirm hover is driven by Modifier.spatialHoverEffect, not hand-rolled scale' "${PATHS[@]}"
scan 'animateFloatAsState\(' info 'R3 animateFloatAsState detected near hover scope — confirm it is not hand-rolling hover; prefer Modifier.spatialHoverEffect' "${PATHS[@]}"

# ---------- R4 — window-root background respects system glass ----------
echo "-- R4 window-root background"
# Forbidden: hardcoded color background anywhere
scan_without_fixed_figma_color 'Modifier\.background\(\s*Color\(0x' error 'R4 hardcoded color literal as background; use PicoTheme.colorScheme.* and respect the system glass' "${PATHS[@]}"
# Forbidden: stacking glass + solid color on the same chain (best-effort regex)
scan 'backgroundMaterial\([^)]*\)[ \t\r\n.]*background\(' error 'R4 stacking backgroundMaterial + .background — pick exactly one' "${PATHS[@]}"

# Window root containers — emit info reminders so the reviewer can confirm
# the right off-switch was flipped. Use ERE-safe alternation (bare `|`) since
# the previous escaped form (`\|`) does not work under POSIX -E.
container_files=$("$GREP_BIN" "${GREP_FLAGS[@]}" "${EXCLUDES[@]}" -l \
    'DefaultWindowContainer|WindowContainer[ \t]*\(|Augment[ \t]*\(|Subwindow[ \t]*\{|Stage[ \t]*\{' \
    "${PATHS[@]}" 2>/dev/null || true)
if [[ -n "$container_files" ]]; then
  override_files=$("$GREP_BIN" "${GREP_FLAGS[@]}" "${EXCLUDES[@]}" -l \
      'backgroundMaterial\([^)]*enable[ \t]*=[ \t]*true|design-style:[ \t]*opaque-root' \
      "${PATHS[@]}" 2>/dev/null || true)
  if [[ -n "$override_files" ]]; then
    echo "[info] R4 custom-glass / opaque-root override detected — confirm the right off-switch is set:"
    echo "       - DefaultWindowContainer  → AndroidManifest \`pico.spatial.windowcontainer.materialbackground=\"0\"\`"
    echo "       - WindowContainer(...) / Augment(...) → DSL parameter \`enableMaterialBackground = false\`"
    echo "       - Stage { ... } has no glass switch; no override is expected on its root"
    while IFS= read -r f; do
      [[ -z "$f" ]] && continue
      printf '       %s\n' "$f"
    done <<<"$override_files"
  fi
fi
# Soft check: any non-default WindowContainer(...) call should declare
# enableMaterialBackground explicitly when overriding the default behavior.
# Note: deep semantic check — "root Box paints solid color while DSL switch is
# still default true" — is delegated to the reviewer LLM (see judge.md);
# grep cannot reliably localize the root Box across multi-line layouts.
if "$GREP_BIN" "${GREP_FLAGS[@]}" "${EXCLUDES[@]}" -l 'WindowContainer[ \t]*\(' "${PATHS[@]}" >/dev/null 2>&1; then
  scan 'WindowContainer\([^)]*enableMaterialBackground[ \t]*=[ \t]*true[^)]*\)' info 'R4 WindowContainer(... enableMaterialBackground = true ...) — defaults to true; explicit `true` is fine, but make sure no solid background is painted on the root' "${PATHS[@]}"
  scan 'properties[ \t]*=[ \t]*\{[^}]*enableMaterialBackground[ \t]*=[ \t]*false' info 'R4 properties = { enableMaterialBackground = false } — DSL-style off-switch detected; confirm root Box uses backgroundMaterial(...) or `// design-style: opaque-root` + Modifier.background(<role>)' "${PATHS[@]}"
fi

# ---------- R5 — theme-role routing ----------
echo "-- R5 theme-role routing"
scan_without_fixed_figma_color 'Color\(0x[0-9A-Fa-f]{6,8}\)' error 'R5 hardcoded color literal; use PicoTheme.colorScheme.* unless this is an annotated fixed Figma/screenshot color' "${PATHS[@]}"
scan 'TextStyle\(fontSize\s*=' error 'R5 hardcoded typography; use PicoTheme.typography.*' "${PATHS[@]}"
scan 'Modifier\.alpha\(\s*0\.3f\s*\)' error 'R5 hardcoded disabled alpha 0.3f; use LocalDisableAlpha.current' "${PATHS[@]}"

# ---------- R6 — indication / haptics ----------
echo "-- R6 indication & haptics"
# Per-file check: a file that uses .clickable( MUST also reference
# LocalIndication.current and controllerHapticFeedback somewhere in the same
# file. (The previous tree-wide `require` was too lenient and never failed in
# practice.)
clickable_files=$("$GREP_BIN" "${GREP_FLAGS[@]}" "${EXCLUDES[@]}" -l '\.clickable\(' "${PATHS[@]}" 2>/dev/null || true)
if [[ -n "$clickable_files" ]]; then
  while IFS= read -r f; do
    [[ -z "$f" ]] && continue
    if ! file_has_code_pattern '\.clickable\(' "$f"; then
      continue
    fi
    if ! file_has_code_pattern 'LocalIndication\.current' "$f"; then
      printf '[warning] R6 clickable() without LocalIndication.current :: %s\n' "$f"
      WARNINGS=$((WARNINGS+1))
    fi
    if ! file_has_code_pattern 'controllerHapticFeedback' "$f"; then
      printf '[error] R6 clickable() without shared controllerHapticFeedback :: %s\n' "$f"
      ERRORS=$((ERRORS+1))
    fi
  done <<<"$clickable_files"
fi

# ---------- R7 — library-private tokens ----------
echo "-- R7 library-private token imports"
scan 'import com\.pico\.spatial\.ui\.design\.tokens\.(DimensionTokens|ColorTokens)' error 'R7 do not import @RestrictTo(LIBRARY) tokens' "${PATHS[@]}"

# ---------- R8 — migrated SpatialUI checklist heuristics ----------
echo "-- R8 migrated SpatialUI checklist heuristics"
scan 'import androidx\.compose\.material3\.(Button|Text|Icon|IconButton|AlertDialog|Slider|Switch|Checkbox|TextField)' error 'R8 Material3 component import; prefer com.pico.spatial.ui.design.* built-ins' "${PATHS[@]}"
scan 'import com\.pico\.spatial\.ui\.design\.AlertDialog' error 'R8 AlertDialog belongs to com.pico.spatial.ui.design.windows.AlertDialog' "${PATHS[@]}"
scan 'collectAsState\([ 	]*\)' warning 'R8 collectAsState() in UI; prefer collectAsStateWithLifecycle() for ViewModel state' "${PATHS[@]}"
scan 'key[ 	]*=[ 	]*\{[ 	]*(index|it\.hashCode\(\))[ 	]*\}' warning 'R8 unstable lazy key; prefer stable item id' "${PATHS[@]}"
scan 'remember[ 	]*\{[ 	]*mutableStateOf\((true|false)\)[ 	]*\}' info 'R8 remember boolean UI state; use rememberSaveable for popup/dialog/menu/subwindow visibility' "${PATHS[@]}"
scan '\.padding\([^)]*horizontal[ 	]*=[^)]*,[^)]*(bottom|top|start|end)[ 	]*=' error 'R8 invalid Modifier.padding overload; use start/end/top/bottom instead of mixing horizontal with side params' "${PATHS[@]}"
scan 'PicoTheme\.colorScheme\.(accent|primary|secondary|background|surface|onSurface|onPrimary)\b' error 'R8 guessed Material-style colorScheme role; use PicoTheme roles or Vibrant' "${PATHS[@]}"
scan '\.background\([^)]*,[ 	]*(RoundedCornerShape|CircleShape|shape)[^)]*\)\.clickable' warning 'R8 background(shape).clickable can mismatch hover/hit shape; prefer clip(shape).spatialHoverEffect().clickable().background()' "${PATHS[@]}"
scan '\.clickable(\([^)]*\))?[ 	.]*\.spatialHoverEffect\(' warning 'R8 hover after clickable; prefer clip().spatialHoverEffect().clickable()' "${PATHS[@]}"
scan '\.background\([^)]*\)\.(fillMaxWidth|fillMaxSize|width|height|size)\(' warning 'R8 layout modifier after background; put size/layout before decoration' "${PATHS[@]}"
scan 'modifier\.(fillMaxWidth|fillMaxSize|width|height|size)\(' info 'R8 fixed layout appended to incoming modifier; confirm caller override is not blocked or use Modifier.defaults.then(modifier)' "${PATHS[@]}"
scan 'padding\((start|bottom|end|top)[ 	]*=[ 	]*[0-9]{2,3}\.dp' info 'R8 large directional padding detected; confirm this is not manual TabBar/Toolbar avoidance' "${PATHS[@]}"
scan 'Text\("(✕|×|x|X)"' warning 'R8 handmade close glyph; prefer IconButton + vector icon' "${PATHS[@]}"
scan 'https?://(picsum\.photos|placehold\.co|via\.placeholder\.com|dummyimage\.com)' warning 'R8 hardcoded placeholder URL in UI; bind image URLs from uiState/repository data' "${PATHS[@]}"

# ---------- summary ----------
echo
echo "==> spatial-ui-design-style verifier summary"
printf '    errors:   %d\n' "$ERRORS"
printf '    warnings: %d\n' "$WARNINGS"

if [[ $ERRORS -gt 0 ]]; then
  echo "    result:   FAIL"
  exit 1
fi
echo "    result:   PASS"
exit 0
