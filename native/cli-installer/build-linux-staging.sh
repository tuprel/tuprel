#!/usr/bin/env bash
set -euo pipefail
source_dir="$(cd -- "$(dirname -- "$0")" && pwd)"
output="${1:?absolute path to the staging library required}"
[[ "$output" == /* ]]
gcc -std=c17 -O2 -Wall -Wextra -Werror -pedantic -fstack-protector-strong \
    -D_POSIX_C_SOURCE=200809L -D_FORTIFY_SOURCE=3 -fPIC -shared -Wl,-z,relro,-z,now,--build-id=none \
    -I"${JAVA_HOME:?JDK required}/include" -I"$JAVA_HOME/include/linux" \
    "$source_dir/linux-root.c" "$source_dir/staging-root-jni.c" -o "$output"
