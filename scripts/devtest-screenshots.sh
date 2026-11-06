#!/usr/bin/env bash
# Headless screenshot harness for Waypy (dev only).
#
# Launches the client with the devtest mod (src/devtest) under Xvfb with software OpenGL (llvmpipe),
# quick-plays into the `waypy-devtest` superflat world (created on first run), runs the scripted
# scenario, writes PNGs to run-devtest/screenshots/ and quits.
#
# Usage (from the repo root):   scripts/devtest-screenshots.sh [--fresh]
#   --fresh   delete the devtest world first (it is recreated deterministically)
# Env:
#   DEVTEST_TIMEOUT   overall wall-clock limit in seconds (default 1200)
#   JAVA_HOME         defaults to /usr/lib/jvm/java-25-openjdk-amd64 if that exists
set -euo pipefail

cd "$(dirname "$0")/.."
ROOT="$(pwd)"
RUN_DIR="$ROOT/run-devtest"
TIMEOUT="${DEVTEST_TIMEOUT:-1200}"

if [[ "${1:-}" == "--fresh" ]]; then
	rm -rf "$RUN_DIR/saves/waypy-devtest" "$RUN_DIR/waypy/sp_waypy-devtest.json"
fi

if [[ -z "${JAVA_HOME:-}" || ! -x "${JAVA_HOME}/bin/java" ]] || ! "${JAVA_HOME}/bin/java" -version 2>&1 | grep -q 'version "25'; then
	if [[ -d /usr/lib/jvm/java-25-openjdk-amd64 ]]; then
		export JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64
	fi
fi

command -v xvfb-run >/dev/null || { echo "xvfb-run not found" >&2; exit 1; }

mkdir -p "$RUN_DIR"
rm -rf "$RUN_DIR/screenshots"
rm -f "$RUN_DIR/devtest-result.txt"
LOG="$RUN_DIR/devtest-run.log"

# Software GL: llvmpipe gives GL 4.5 core. No audio device is fine (OpenAL fails softly).
export LIBGL_ALWAYS_SOFTWARE=1
export GALLIUM_DRIVER=llvmpipe
export MESA_GL_VERSION_OVERRIDE="${MESA_GL_VERSION_OVERRIDE:-4.5}"
export ALSOFT_DRIVERS="${ALSOFT_DRIVERS:-null}"

echo "Running devtest scenario (log: $LOG, timeout ${TIMEOUT}s)..."
start=$(date +%s)
set +e
timeout --kill-after=30 "$TIMEOUT" \
	xvfb-run -a -s "-screen 0 1280x720x24" \
	./gradlew --console=plain runDevtest >"$LOG" 2>&1
status=$?
set -e
end=$(date +%s)

grep -h '\[devtest\]' "$LOG" | sed 's/^.*\[devtest\] /  /' || true
echo "Exit status: $status (took $((end - start))s)"

shots=()
if [[ -d "$RUN_DIR/screenshots" ]]; then
	while IFS= read -r f; do shots+=("$f"); done < <(find "$RUN_DIR/screenshots" -maxdepth 1 -name '*.png' | sort)
fi

if [[ -f "$RUN_DIR/devtest-result.txt" ]] && head -1 "$RUN_DIR/devtest-result.txt" | grep -q '^status=OK$' && [[ $status -eq 0 ]]; then
	echo "Screenshots:"
	printf '  %s\n' "${shots[@]}"
	exit 0
fi

echo "Devtest run FAILED (exit $status). See $LOG" >&2
[[ ${#shots[@]} -gt 0 ]] && { echo "Partial screenshots:" >&2; printf '  %s\n' "${shots[@]}" >&2; }
[[ $status -eq 0 ]] && status=1
exit $status
