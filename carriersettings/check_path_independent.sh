#!/bin/sh
# Prove that OcdCarrierSettingsDataset's code does not depend on where a ROM puts this folder.
#
#   carriersettings/check_path_independent.sh ANDROID_JAR [D8]
#
# Soong compiles a module's Kotlin with -module-name set to its directory, sub-directory and
# name joined with "__" (build/soong/java/kotlin.go), for example
# vendor__extra__carriersettings__android_common__OcdCarrierSettingsDataset. Kotlin bakes that
# name into every `internal` member's JVM name, and D8 and R8 then number synthetic classes
# differently (OCD rule round 37, step 3, 2026-10-30: the S20 built from vendor/extra and from
# packages/apps/OcdCarrierSettings differed only in classes.dex for that reason). This script:
# - refuses the declarations that put the module name or a path into class files: `internal`,
#   @JvmName on a file, @JvmMultifileClass;
# - compiles ocd/src twice with Soong's flags, once under each of two ROM paths' module names, and
#   requires every class file to be byte-identical (the .kotlin_module file is named after the
#   module and never reaches the APK; its content must match too);
# - with D8 (an SDK build-tools d8), dexes both and requires identical classes.dex.
# Like run.sh it takes the Kotlin compiler from Gradle's cache and points the two hidden
# restoreFromXml calls at run.sh's stand-in (ocd/test/stubs); both compiles get the same input.
set -eu

android=${1:?usage: check_path_independent.sh ANDROID_JAR [D8]}
d8=${2:-}
here=$(cd "$(dirname "$0")" && pwd)
module=$here/ocd
gradle=${GRADLE_USER_HOME:-$HOME/.gradle}/caches/modules-2/files-2.1
java=${JAVA:-java}

status=0
found=$(grep -rnE '^\s*(\w+\s+)*internal\s|@file:JvmName|@JvmName|@JvmMultifileClass' "$module/src" || true)
if [ -n "$found" ]; then
    echo "declarations that put Soong's module name or a path into the code:" >&2
    echo "$found" >&2
    status=1
fi

jar() { find "$gradle/$1" -name "$2" ! -name '*-sources.jar' | sort | tail -n 1; }
kotlin=2.2.21
compiler=$(jar org.jetbrains.kotlin/kotlin-compiler-embeddable "kotlin-compiler-embeddable-$kotlin.jar")
stdlib=$(jar org.jetbrains.kotlin/kotlin-stdlib "kotlin-stdlib-$kotlin.jar")
[ -n "$compiler" ] && [ -n "$stdlib" ] || { echo "no Kotlin $kotlin compiler in Gradle's cache ($gradle)" >&2; exit 2; }
cp="$compiler:$stdlib:$(jar org.jetbrains.kotlin/kotlin-script-runtime "kotlin-script-runtime-$kotlin.jar")"
cp="$cp:$(jar org.jetbrains.kotlin/kotlin-reflect 'kotlin-reflect-*.jar'):$(jar org.jetbrains.kotlin/kotlin-daemon-embeddable "kotlin-daemon-embeddable-$kotlin.jar")"
cp="$cp:$(jar org.jetbrains.kotlinx/kotlinx-coroutines-core-jvm 'kotlinx-coroutines-core-jvm-*.jar'):$(jar org.jetbrains/annotations 'annotations-13.0.jar')"

out=$(mktemp -d)
trap 'rm -rf "$out"' EXIT
mkdir -p "$out/src"
for f in $(cd "$module" && find src -name '*.kt' | sort) test/stubs/Stubs.kt test/stubs/StubsPlatform.kt; do
    sed 's/PersistableBundle.restoreFromXml(parser)/com.android.carriersettings.restoreFromXmlStandIn(parser)/' "$module/$f" > "$out/src/$(echo "$f" | tr / _)"
done

compile() { # module name, output dir
    # Soong's flags for a device Kotlin module (build/soong/java/base.go).
    "$java" -cp "$cp" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -Xsuppress-version-warnings -Xconsistent-data-class-copy-visibility \
        -Xsam-conversions=class -no-stdlib -no-jdk -jvm-target 17 -module-name "$1" \
        -cp "$stdlib:$android" -d "$2" "$out"/src/*.kt 2>&1 | grep -v '^warning\|^WARNING\|^$' || true
    [ -f "$2/com/android/carriersettings/ocd/Engine.class" ] || { echo "the provider did not compile as $1" >&2; exit 2; }
}
a=vendor__extra__carriersettings__android_common__OcdCarrierSettingsDataset
b=packages__apps__OcdCarrierSettings__carriersettings__android_common__OcdCarrierSettingsDataset
compile "$a" "$out/a"
compile "$b" "$out/b"
cmp -s "$out/a/META-INF/$a.kotlin_module" "$out/b/META-INF/$b.kotlin_module" || { echo "the .kotlin_module files differ in content" >&2; status=1; }
rm -f "$out/a/META-INF/$a.kotlin_module" "$out/b/META-INF/$b.kotlin_module"
classes=$(cd "$out/a" && find . -name '*.class' | wc -l)
if diff -r "$out/a" "$out/b" > "$out/diff"; then
    echo "class files: all $classes byte-identical under $a and $b"
else
    echo "class files differ between the two module names:" >&2
    sed 's|'"$out"'/||g' "$out/diff" | head -20 >&2
    for c in $(cd "$out/a" && find . -name '*.class' | sort); do
        cmp -s "$out/a/$c" "$out/b/$c" || echo "  differs: $c" >&2
    done
    for c in $(cd "$out/b" && find . -name '*.class' | sort); do
        [ -e "$out/a/$c" ] || echo "  only under $b: $c" >&2
    done
    status=1
fi

if [ -n "$d8" ]; then
    for side in a b; do
        (cd "$out/$side" && find . -name '*.class' | sort > "$out/$side.list")
        mkdir -p "$out/$side-dex"
        (cd "$out/$side" && "$d8" --release --min-api 31 --lib "$android" --classpath "$stdlib" --output "$out/$side-dex" $(cat "$out/$side.list"))
    done
    if cmp -s "$out/a-dex/classes.dex" "$out/b-dex/classes.dex"; then
        echo "classes.dex: byte-identical ($(wc -c < "$out/a-dex/classes.dex") bytes, D8 $("$d8" --version 2>/dev/null | head -1))"
    else
        echo "classes.dex differs between the two module names" >&2
        status=1
    fi
fi
exit "$status"
