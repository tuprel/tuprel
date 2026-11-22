#!/usr/bin/env bash
# Disposable CI mount/network namespace; native filesystem policy is unchanged.
set -euo pipefail
prepared="$1"
single="$2"
jdk21="$3"
jdk25="$4"
checkout="$5"
uid_value="$6"
gid_value="$7"
test "$(id -u)" = 0
test "$uid_value" -gt 0
cd /
mount --make-rprivate /
# Keep the native ext4/XFS mount topology. Docker overlay roots and bind aliases
# are deliberately unsupported by the existing validator, not exceptions to it.
for directory in /opt/hostedtoolcache /usr/lib/jvm "$checkout"; do
  test -d "$directory"
  mount -t tmpfs -o ro,mode=0555 tmpfs "$directory"
done
test ! -e "$jdk21/bin/java"
test ! -e "$jdk25/bin/java"
test ! -e /usr/bin/java
test ! -e /usr/bin/javac
test ! -e "$checkout/gradlew"
exec setpriv --reuid="$uid_value" --regid="$gid_value" --clear-groups \
  --bounding-set=-all --no-new-privs \
  env -i HOME="$single" JAVA_HOME="$prepared/jdk17" \
    PATH="$prepared/jdk17/bin:/usr/bin:/bin" \
    TUPREL_PREPARED_DIRECTORY="$prepared" TUPREL_SINGLE_ROOT="$single" \
    /bin/bash "$prepared/single-jdk.sh"
