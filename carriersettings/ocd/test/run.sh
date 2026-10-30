#!/bin/sh
# Off-device checks for OCD's dataset in OcdCarrierSettings (OCD rule round 30). No Android
# build: the reader, matcher, bundle order, APN logic, guard, store and engine are plain Kotlin
# (ocd/src/.../ocd/*.kt without the Android glue), compiled with the Kotlin compiler from
# Gradle's cache and tested with JUnit 4, among them the spec's 27 matcher vectors, the
# refusals of its section 2.12, and round 32's APN candidate trials (CandidateTest).
#
#   carriersettings/ocd/test/run.sh [ANDROID_JAR]
#
# With ANDROID_JAR (an SDK platforms/android-NN/android.jar) it also compile-checks the Android
# glue (OcdAndroid, DeviceOverride, the providers, receivers and the service) against that jar
# under -Werror, with stand-ins for the generated R class and two hidden platform calls
# (ocd/test/stubs). That proves the glue uses only APIs that exist; it does not replace the
# build in the Android tree.
#
# OCD_APNS_CONF may name OCD's public generated/android/apns-conf.xml; the APN tests then also
# read the whole file strictly.
set -eu

here=$(cd "$(dirname "$0")" && pwd)
module=$(dirname "$here")
gradle=${GRADLE_USER_HOME:-$HOME/.gradle}/caches/modules-2/files-2.1
java=${JAVA:-java}

jar() { find "$gradle/$1" -name "$2" ! -name '*-sources.jar' | sort | tail -n 1; }
kotlin=2.2.21
compiler=$(jar org.jetbrains.kotlin/kotlin-compiler-embeddable "kotlin-compiler-embeddable-$kotlin.jar")
stdlib=$(jar org.jetbrains.kotlin/kotlin-stdlib "kotlin-stdlib-$kotlin.jar")
cp="$compiler:$stdlib:$(jar org.jetbrains.kotlin/kotlin-script-runtime "kotlin-script-runtime-$kotlin.jar")"
cp="$cp:$(jar org.jetbrains.kotlin/kotlin-reflect 'kotlin-reflect-*.jar'):$(jar org.jetbrains.intellij.deps/trove4j 'trove4j-*.jar')"
cp="$cp:$(jar org.jetbrains.kotlin/kotlin-daemon-embeddable "kotlin-daemon-embeddable-$kotlin.jar")"
cp="$cp:$(jar org.jetbrains.kotlinx/kotlinx-coroutines-core-jvm 'kotlinx-coroutines-core-jvm-*.jar'):$(jar org.jetbrains/annotations 'annotations-13.0.jar')"
junit=$(jar junit/junit 'junit-4.13.2.jar'):$(jar org.hamcrest/hamcrest-core 'hamcrest-core-1.3.jar')

out=$(mktemp -d)
trap 'rm -rf "$out"' EXIT
src="$module/src/com/android/carriersettings/ocd"
# The pure core: every file of ocd/ that does not touch android.*.
pure=""
glue=""
for f in "$src"/*.kt; do
    if grep -q '^import android\.\|^import com\.android\.carriersettings\.R$' "$f"; then glue="$glue $f"; else pure="$pure $f"; fi
done

kotlinc() { "$java" -cp "$cp" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect -jvm-target 17 -Werror "$@" 2>&1 | grep -v '^WARNING\|^$' || true; }

echo "compiling the pure dataset code and its tests"
# shellcheck disable=SC2086
kotlinc -cp "$stdlib:$junit" -d "$out/classes" $pure "$here"/src/*.kt
[ -f "$out/classes/com/android/carriersettings/ocd/Engine.class" ] || { echo "the pure code did not compile" >&2; exit 1; }
mkdir -p "$out/tmp"
"$java" -Djava.io.tmpdir="$out/tmp" -cp "$out/classes:$stdlib:$junit" org.junit.runner.JUnitCore \
    com.android.carriersettings.ocd.VectorsTest com.android.carriersettings.ocd.ReaderTest \
    com.android.carriersettings.ocd.BundleTest com.android.carriersettings.ocd.ApnTest \
    com.android.carriersettings.ocd.EngineTest com.android.carriersettings.ocd.CandidateTest

if [ $# -ge 1 ]; then
    android=$1
    echo "compile-checking the Android glue against $android"
    # LineageOS's override lines, and DeviceOverride reading the same XML and an AOSP vendor.xml,
    # call PersistableBundle.restoreFromXml, which the public SDK hides; the check compiles copies
    # with that one call pointed at a stand-in.
    mkdir -p "$out/patched"
    sed 's/PersistableBundle.restoreFromXml(parser)/com.android.carriersettings.restoreFromXmlStandIn(parser)/' \
        "$module/src/com/android/carriersettings/CarrierConfigService.kt" > "$out/patched/CarrierConfigService.kt"
    grep -q restoreFromXmlStandIn "$out/patched/CarrierConfigService.kt" || { echo "the service no longer calls restoreFromXml; update run.sh" >&2; exit 1; }
    plain_glue=""
    for f in $glue; do
        if grep -q 'PersistableBundle.restoreFromXml(parser)' "$f"; then
            sed 's/PersistableBundle.restoreFromXml(parser)/com.android.carriersettings.restoreFromXmlStandIn(parser)/' "$f" > "$out/patched/$(basename "$f")"
        else
            plain_glue="$plain_glue $f"
        fi
    done
    # shellcheck disable=SC2086
    kotlinc -cp "$stdlib:$android" -d "$out/glue" $pure $plain_glue "$out"/patched/*.kt "$here"/stubs/*.kt
    [ -f "$out/glue/com/android/carriersettings/CarrierConfigService.class" ] || { echo "the glue did not compile" >&2; exit 1; }
    echo "glue: compiles against $(basename "$(dirname "$android")")"
fi
