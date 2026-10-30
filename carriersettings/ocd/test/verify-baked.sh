#!/bin/sh
# The phone's own reader over a baked dataset, off the phone: checks 1 to 10 as the build's
# dataset, the serving index, and bundles for a few SIMs, with no carrier-less record served
# (round 31; ocd/test/tools/VerifyBaked.kt). S20_Project scripts/ocd-pin-main.py runs it before it
# commits a pin.
#
#   carriersettings/ocd/test/verify-baked.sh DATASET_DIR [OCD_BUILD_JSON]
set -eu

here=$(cd "$(dirname "$0")" && pwd)
module=$(dirname "$here")
dataset=${1:?usage: verify-baked.sh DATASET_DIR [OCD_BUILD_JSON]}
build=${2:-$(dirname "$module")/ocd-build.json}
gradle=${GRADLE_USER_HOME:-$HOME/.gradle}/caches/modules-2/files-2.1
java=${JAVA:-java}

jar() { find "$gradle/$1" -name "$2" ! -name '*-sources.jar' | sort | tail -n 1; }
kotlin=2.2.21
compiler=$(jar org.jetbrains.kotlin/kotlin-compiler-embeddable "kotlin-compiler-embeddable-$kotlin.jar")
stdlib=$(jar org.jetbrains.kotlin/kotlin-stdlib "kotlin-stdlib-$kotlin.jar")
[ -n "$compiler" ] && [ -n "$stdlib" ] || { echo "no Kotlin $kotlin compiler in Gradle's cache ($gradle)" >&2; exit 2; }
cp="$compiler:$stdlib:$(jar org.jetbrains.kotlin/kotlin-script-runtime "kotlin-script-runtime-$kotlin.jar")"
cp="$cp:$(jar org.jetbrains.kotlin/kotlin-reflect 'kotlin-reflect-*.jar'):$(jar org.jetbrains.intellij.deps/trove4j 'trove4j-*.jar')"
cp="$cp:$(jar org.jetbrains.kotlin/kotlin-daemon-embeddable "kotlin-daemon-embeddable-$kotlin.jar")"
cp="$cp:$(jar org.jetbrains.kotlinx/kotlinx-coroutines-core-jvm 'kotlinx-coroutines-core-jvm-*.jar'):$(jar org.jetbrains/annotations 'annotations-13.0.jar')"

out=$(mktemp -d)
trap 'rm -rf "$out"' EXIT
pure=""
for f in "$module"/src/com/android/carriersettings/ocd/*.kt; do
    grep -q '^import android\.\|^import com\.android\.carriersettings\.R$' "$f" || pure="$pure $f"
done
# shellcheck disable=SC2086
"$java" -cp "$cp" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect -jvm-target 17 -cp "$stdlib" -d "$out" $pure "$here/tools/VerifyBaked.kt" 2>&1 | grep -v '^WARNING\|^$' || true
[ -f "$out/com/android/carriersettings/ocd/VerifyBakedKt.class" ] || { echo "the reader did not compile" >&2; exit 2; }
mkdir -p "$out/tmp"
"$java" -Djava.io.tmpdir="$out/tmp" -cp "$out:$stdlib" com.android.carriersettings.ocd.VerifyBakedKt "$dataset" "$build"
