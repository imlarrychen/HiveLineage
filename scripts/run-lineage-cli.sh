#!/usr/bin/env bash
set -euo pipefail

CONFIG_PATH="${1:-config/lineage-analyzer.properties}"

if [[ ! -f "${CONFIG_PATH}" ]]; then
  echo "Config file not found: ${CONFIG_PATH}"
  echo "Copy config/lineage-analyzer.example.properties to config/lineage-analyzer.properties and adjust it first."
  exit 1
fi

mvn -q -DskipTests package dependency:build-classpath -Dmdep.outputFile=target/lineage-classpath.txt

CP="target/classes:$(tr ':' '\n' < target/lineage-classpath.txt | grep '\.jar$' | paste -sd ':' -)"
JAVA_ARGS=()
JAVA_MAJOR="$(java -version 2>&1 | awk -F '[\".]' '/version/ {print $2; exit}')"

if [[ "${JAVA_MAJOR}" != "1" && "${JAVA_MAJOR}" -ge 9 ]]; then
  JAVA_ARGS+=("--add-opens=java.base/java.net=ALL-UNNAMED")
fi

java "${JAVA_ARGS[@]}" -cp "${CP}" com.panfan.hivelineage.cli.LineageAnalyzeCli "${CONFIG_PATH}"
