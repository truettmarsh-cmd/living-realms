#!/bin/sh
# A fresh server instance for kingdom/raid integration assertions. Never edits existing worlds.
set -eu
cd "$(dirname "$0")/.."
warfare_eula=${WARFARE_EULA_FILE:-run-military-test/eula.txt}
rg -q '^eula\s*=\s*true\s*$' "$warfare_eula" || { echo 'Provide an existing accepted EULA with WARFARE_EULA_FILE'; exit 1; }
warfare_run=$(mktemp -d "${TMPDIR:-/tmp}/livingrealms-warfare.XXXXXX")
cp "$warfare_eula" "$warfare_run/eula.txt"
cat > "$warfare_run/server.properties" <<'PROPERTIES'
server-ip=127.0.0.1
server-port=0
online-mode=false
level-name=test
level-type=minecraft:flat
generate-structures=false
view-distance=3
simulation-distance=3
max-tick-time=-1
PROPERTIES
export JAVA_HOME=${JAVA_HOME:-$HOME/.gradle/jdks/eclipse_adoptium-17-aarch64-os_x/jdk-17.0.20.1+1/Contents/Home}
printf '%s\n' "Warfare test instance: $warfare_run"
warfare_exit=0
"${GRADLE_BIN:-./gradlew}" runServer -PwarfareTest "-PwarfareTestDir=$warfare_run" "$@" > "$warfare_run/launch.log" 2>&1 || warfare_exit=$?
if [ "$warfare_exit" -ne 0 ] || ! rg -q '^PASS:' "$warfare_run/warfare-test-result.txt"; then tail -n 100 "$warfare_run/launch.log"; exit 1; fi
cat "$warfare_run/warfare-test-result.txt"
