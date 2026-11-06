#!/usr/bin/env bash
set -euo pipefail

source_dir="$(cd -- "$(dirname -- "$0")" && pwd)"
output="${1:?absolute path to the native test binary required}"
[[ "$output" == /* ]]
gcc -std=c17 -O2 -Wall -Wextra -Werror -pedantic -fstack-protector-strong \
    -D_FORTIFY_SOURCE=3 -fPIE -pie -Wl,-z,relro,-z,now,--build-id=none \
    "$source_dir/linux.c" -o "$output"
