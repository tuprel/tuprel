#!/usr/bin/env bash
set -euo pipefail
test "$JAVA_HOME" = /opt/java/openjdk
cd -- "$TUPREL_CONSUMER_PROJECT"
"$TUPREL_CONSUMER_MAVEN/bin/mvn" --version
"$TUPREL_CONSUMER_MAVEN/bin/mvn" --offline --batch-mode --no-transfer-progress --strict-checksums \
  --settings "$TUPREL_MAVEN_SETTINGS" --global-settings "$TUPREL_MAVEN_SETTINGS" \
  "-Dmaven.repo.local=$TUPREL_CONSUMER_MAVEN_CACHE" verify
"$JAVA_HOME/bin/java" -cp target/test-classes example.products.PackagedProductsProof "$TUPREL_FROZEN_JAR"
