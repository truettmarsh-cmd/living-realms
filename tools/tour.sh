#!/bin/sh
# Automated in-game test: founds a town in a fresh flat world, builds, trains soldiers and screenshots every page
# into run/screenshots.
cd "$(dirname "$0")/.." || exit 1
rm -rf run/saves/livingrealms_tour run/screenshots/livingrealms_*.png
JAVA_HOME=${JAVA_HOME:-$HOME/.gradle/jdks/eclipse_adoptium-17-aarch64-os_x/jdk-17.0.20.1+1/Contents/Home} \
  ./gradlew runClient -Ptour "$@" > run-tour.log 2>&1
grep -E "livingrealms tour|Living Realms|Exception|Caused by|ERROR" run-tour.log | head -80
