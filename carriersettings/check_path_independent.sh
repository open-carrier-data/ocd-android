#!/bin/sh
# Prove that OcdCarrierSettingsDataset's code does not depend on where a ROM puts this folder.
#
#   carriersettings/check_path_independent.sh ANDROID_JAR [R8_JAR]
#
# Soong compiles a module's Kotlin with -module-name set to its directory, sub-directory and
# name joined with "__" (build/soong/java/kotlin.go), for example
# vendor__extra__carriersettings__android_common__OcdCarrierSettingsDataset. Kotlin bakes that
# name into every `internal` member's JVM name, and R8 then numbers synthetic classes
# differently (OCD rule round 37, step 3, 2026-10-30: the S20 built from vendor/extra and from
# packages/apps/OcdCarrierSettings differed only in classes.dex for that reason). This script:
# - refuses the declarations that put the module name into code: `internal`, @JvmName,
#   @JvmMultifileClass;
# - compiles ocd/src twice with Soong's Kotlin flags, under the module names of two ROM paths,
#   and requires the same classes with the same members, signatures and bytecode (javap). Their
#   class files differ only in the kotlin.Metadata annotation, which records the module name;
#   the ROM build's R8 drops that annotation (build/make/core/proguard/kotlin.flags keeps no
#   kotlin.Metadata);
# - with R8_JAR (an SDK build-tools lib/d8.jar, which holds R8), shrinks and optimizes both as
#   Soong does for this app (no obfuscation, Soong's map-id and source-file templates, the
#   manifest's entry points kept) and requires a byte-identical classes.dex.
# Like run.sh it takes the Kotlin compiler from Gradle's cache and points the two hidden
# restoreFromXml calls at run.sh's stand-ins (ocd/test/stubs, compiled as a library).
set -eu

android=${1:?usage: check_path_independent.sh ANDROID_JAR [R8_JAR]}
r8=${2:-}
here=$(cd "$(dirname "$0")" && pwd)
module=$here/ocd
gradle=${GRADLE_USER_HOME:-$HOME/.gradle}/caches/modules-2/files-2.1
java=${JAVA:-java}
javap=${JAVAP:-javap}

status=0
found=$(grep -rnE '^\s*(\w+\s+)*internal\s|@JvmName|@JvmMultifileClass' "$module/src" || true)
if [ -n "$found" ]; then
    echo "declarations that put Soong's module name into the code:" >&2
    echo "$found" >&2
    status=1
fi

gjar() { find "$gradle/$1" -name "$2" ! -name '*-sources.jar' | sort | tail -n 1; }
kotlin=2.2.21
compiler=$(gjar org.jetbrains.kotlin/kotlin-compiler-embeddable "kotlin-compiler-embeddable-$kotlin.jar")
stdlib=$(gjar org.jetbrains.kotlin/kotlin-stdlib "kotlin-stdlib-$kotlin.jar")
[ -n "$compiler" ] && [ -n "$stdlib" ] || { echo "no Kotlin $kotlin compiler in Gradle's cache ($gradle)" >&2; exit 2; }
cp="$compiler:$stdlib:$(gjar org.jetbrains.kotlin/kotlin-script-runtime "kotlin-script-runtime-$kotlin.jar")"
cp="$cp:$(gjar org.jetbrains.kotlin/kotlin-reflect 'kotlin-reflect-*.jar'):$(gjar org.jetbrains.kotlin/kotlin-daemon-embeddable "kotlin-daemon-embeddable-$kotlin.jar")"
cp="$cp:$(gjar org.jetbrains.kotlinx/kotlinx-coroutines-core-jvm 'kotlinx-coroutines-core-jvm-*.jar'):$(gjar org.jetbrains/annotations 'annotations-13.0.jar')"

out=$(mktemp -d)
trap 'rm -rf "$out"' EXIT
mkdir -p "$out/src" "$out/stubs"
for f in $(cd "$module" && find src -name '*.kt' | sort); do
    sed 's/PersistableBundle.restoreFromXml(parser)/com.android.carriersettings.restoreFromXmlStandIn(parser)/' "$module/$f" > "$out/src/$(echo "$f" | tr / _)"
done

kotlinc() { # module name, output dir, classpath, sources...
    name=$1; dir=$2; extra=$3; shift 3
    # Soong's flags for a device Kotlin module (build/soong/java/base.go).
    "$java" -cp "$cp" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -Xsuppress-version-warnings -Xconsistent-data-class-copy-visibility \
        -Xsam-conversions=class -no-stdlib -no-jdk -jvm-target 17 -module-name "$name" \
        -cp "$stdlib:$android$extra" -d "$dir" "$@" 2>&1 | grep -v '^warning\|^WARNING\|^$' || true
}
kotlinc stubs "$out/stubs" "" "$module"/test/stubs/*.kt
[ -f "$out/stubs/com/android/carriersettings/R.class" ] || { echo "the stand-ins did not compile" >&2; exit 2; }
a=vendor__extra__carriersettings__android_common__OcdCarrierSettingsDataset
b=packages__apps__OcdCarrierSettings__carriersettings__android_common__OcdCarrierSettingsDataset
for side in a b; do
    eval "name=\$$side"
    kotlinc "$name" "$out/$side" ":$out/stubs" "$out"/src/*.kt
    [ -f "$out/$side/com/android/carriersettings/ocd/Engine.class" ] || { echo "the provider did not compile as $name" >&2; exit 2; }
    rm -f "$out/$side/META-INF/$name.kotlin_module"
    (cd "$out/$side" && find . -name '*.class' | sort) > "$out/$side.list"
done

if ! cmp -s "$out/a.list" "$out/b.list"; then
    echo "the two module names give different classes:" >&2
    diff "$out/a.list" "$out/b.list" >&2 || true
    status=1
fi
# A class file that differs must differ by the module name its kotlin.Metadata records.
raw=0
for c in $(cat "$out/a.list"); do
    cmp -s "$out/a/$c" "$out/b/$c" && continue
    raw=$((raw + 1))
    if ! grep -qa "$a" "$out/a/$c" || ! grep -qa "$b" "$out/b/$c"; then
        echo "$c differs, and not by the module name alone" >&2
        status=1
    fi
done
for side in a b; do
    # shellcheck disable=SC2046
    (cd "$out/$side" && "$javap" -c -p -s -constants -cp . $(sed 's|^\./||; s|\.class$||; s|/|.|g' "$out/$side.list")) > "$out/$side.javap" 2>&1
done
if cmp -s "$out/a.javap" "$out/b.javap"; then
    echo "classes: $(wc -l < "$out/a.list") with the same members, signatures and bytecode under both module names ($raw class files differ by the module name their kotlin.Metadata records)"
else
    echo "members, signatures or bytecode differ between the two module names:" >&2
    diff "$out/a.javap" "$out/b.javap" | head -20 >&2 || true
    status=1
fi

if [ -n "$r8" ]; then
    # Soong's R8 for an optimized app (build/soong/java/dex.go) with the manifest's entry points.
    cat > "$out/keep.pro" <<'PRO'
-dontobfuscate
-dontwarn kotlin.Metadata
-dontwarn kotlin.annotation.**
-keep class com.android.carriersettings.CarrierConfigService { <init>(); }
-keep class com.android.carriersettings.ocd.OcdDataProvider { <init>(); }
-keep class com.android.carriersettings.ocd.OcdApnProvider { <init>(); }
-keep class com.android.carriersettings.ocd.OcdReceiver { <init>(); }
-keep class com.android.carriersettings.ocd.OcdAlarmReceiver { <init>(); }
PRO
    (cd "$out/stubs" && "${JAR:-$(command -v jar)}" cf "$out/stubs.jar" .)
    for side in a b; do
        mkdir -p "$out/$side-dex"
        (cd "$out/$side" && "${JAR:-$(command -v jar)}" cf "$out/$side.jar" .)
        "$java" -cp "$r8" com.android.tools.r8.R8 --release --min-api 31 --lib "$android" --lib "$out/stubs.jar" --lib "$(gjar org.jetbrains/annotations 'annotations-13.0.jar')" \
            --pg-conf "$out/keep.pro" --map-id-template '%MAP_HASH' --source-file-template 'go/retraceme %MAP_ID' \
            --pg-map-output "$out/$side.map" --output "$out/$side-dex" "$out/$side.jar" "$stdlib" 2>&1 | grep -v '^Warning\|^$' | head -5 || true
        [ -f "$out/$side-dex/classes.dex" ] || { echo "R8 wrote no classes.dex" >&2; exit 2; }
    done
    if cmp -s "$out/a-dex/classes.dex" "$out/b-dex/classes.dex"; then
        echo "classes.dex after R8: byte-identical ($(wc -c < "$out/a-dex/classes.dex") bytes, $("$java" -cp "$r8" com.android.tools.r8.R8 --version 2>/dev/null | head -1 | cut -d' ' -f1,2))"
    else
        echo "classes.dex after R8 differs between the two module names" >&2
        status=1
    fi
fi
exit "$status"
