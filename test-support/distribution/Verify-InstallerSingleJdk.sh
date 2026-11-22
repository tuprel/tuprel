#!/usr/bin/env bash
set -euo pipefail
prepared="$TUPREL_PREPARED_DIRECTORY"
root="$TUPREL_SINGLE_ROOT"
test "$JAVA_HOME" = "$prepared/jdk17"
pin="$(cat "$prepared/key-id")"
cp -a "$prepared/catalogue/." "$root/"
java="$JAVA_HOME/bin/java"
native="$prepared/libtuprel-staging.so"
verifier="$prepared/verify-cli.jar"
"$java" -version
test "$(locale charmap)" = UTF-8
"$java" -XshowSettings:properties -version 2>&1 | grep -E 'java.home =|file.encoding =|sun.jnu.encoding ='
"$java" -cp "$prepared/classes" InstallerRuntimeTest
verify() {
  "$java" --enable-native-access=ALL-UNNAMED -jar "$verifier" "$1" "$root/$1.zip" \
    "$root/$1.manifest" "$root/$1.ed25519" "$root/public-key.bin" "$pin" "$2" "$root" "$native"
}
control() {
  "$java" --enable-native-access=ALL-UNNAMED -cp "$verifier" StagedActivation \
    "$root" "$native" "$root" "$pin" "$@"
}
verify 0.0.0-staging install
verify 0.0.1-staging install
control activate 0.0.0-staging
"$java" --enable-native-access=ALL-UNNAMED -cp "$prepared/classes" SignedCliBundleFixture test-runtime \
  "$root" "$root/0.0.0-staging.zip" "$root/0.0.1-staging.zip" "$pin" "$native"
mkdir -m 700 "$root/consumer café"
cd -- "$root/consumer café"
test "$(control launch --version)" = 'tuprel 0.0.0-staging'
control launch init
control launch validate
control launch generate
mapfile -d '' sources < <(find build/generated/sources/tuprel/main -name '*.java' -print0)
test "${#sources[@]}" -gt 0
"$JAVA_HOME/bin/javac" --release 17 -cp "$root/0.0.0-staging/lib/*:$prepared/consumer-libraries/*" -d build/compiled "${sources[@]}"
control activate 0.0.1-staging
test "$(control launch --version)" = 'tuprel 0.0.1-staging'
if control activate 0.0.0-staging; then exit 1; fi
control rollback 0.0.0-staging
test "$(control launch --version)" = 'tuprel 0.0.0-staging'
verify 0.0.1-staging remove
if verify 0.0.0-staging remove; then exit 1; fi
# Fixture teardown mirrors the existing staging proof, not a public deactivation command.
rm -- "$root/.tuprel-active"
verify 0.0.0-staging remove
test ! -e "$root/0.0.0-staging"
test ! -e "$root/0.0.1-staging"
