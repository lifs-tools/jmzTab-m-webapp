#!/usr/bin/env bash
#
# Copyright 2026 LIFS Tools Consortium.
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#      http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#
# Runs the validator locally as it runs in production: the mzTab-M 2.0 engine
# (port 8084), the mzTab-M 2.1 engine (port 8085) and the webapp
# (http://localhost:8083/mztabvalidator/), each from its packaged jar.
#
# Usage:
#   scripts/run-dev-stack.sh              # builds the jars first if one is missing
#   scripts/run-dev-stack.sh --build      # always builds: ./mvnw -B package -DskipTests
#   scripts/run-dev-stack.sh --no-21      # without the 2.1 engine (a 2.1 file is then unsupported)
#
# The engines run in the background and log to target/dev-stack/engine-2.0.log and
# engine-2.1.log; the webapp runs in the foreground, and Ctrl-C stops all three.
# The webapp is started as the e2e tests expect it (frontend/playwright.config.ts):
# port 8083, context path /mztabvalidator, storage in target/dev-stack/storage.
# Stop the 2.1 engine alone with: kill "$(cat target/dev-stack/engine-2.1.pid)".
#
# Environment: WEBAPP_PORT (8083), ENGINE20_PORT (8084), ENGINE21_PORT (8085),
# STORAGE_LOCATION, JAVA (java on the PATH). Extra arguments after "--" go to the webapp,
# e.g. scripts/run-dev-stack.sh -- --spring.profiles.active=dev
#
# The same three processes as containers: compose/dev-stack.yml.
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$root"

build=auto
with21=true
webapp_args=()
while [[ $# -gt 0 ]]; do
  case "$1" in
    --build) build=always ;;
    --no-21) with21=false ;;
    --) shift; webapp_args=("$@"); break ;;
    -h|--help) sed -n '/^# Runs the validator/,/^# The same three/p' "$0"; exit 0 ;;
    *) echo "Unknown option: $1" >&2; exit 2 ;;
  esac
  shift
done

java_cmd="${JAVA:-java}"
webapp_port="${WEBAPP_PORT:-8083}"
engine20_port="${ENGINE20_PORT:-8084}"
engine21_port="${ENGINE21_PORT:-8085}"
run_dir="$root/target/dev-stack"
storage="${STORAGE_LOCATION:-$run_dir/storage}"
mkdir -p "$run_dir" "$storage"

jar_of() {
  # the executable jar of a module, not the .original of the repackaging
  ls "$1"/target/*.jar 2>/dev/null | grep -v '\.original$' | head -n 1 || true
}

if [[ "$build" == always || -z "$(jar_of server)" || -z "$(jar_of engine-mztabm-2.0)" \
  || ( "$with21" == true && -z "$(jar_of engine-mztabm-2.1)" ) ]]; then
  ./mvnw -B package -DskipTests
fi

pids=()
cleanup() {
  for pid in "${pids[@]}"; do
    kill "$pid" 2>/dev/null || true
  done
  wait 2>/dev/null || true
}
trap cleanup EXIT INT TERM

start_engine() {
  local line="$1" port="$2" jar
  jar="$(jar_of "engine-mztabm-$line")"
  "$java_cmd" -jar "$jar" --server.port="$port" > "$run_dir/engine-$line.log" 2>&1 &
  pids+=("$!")
  echo "$!" > "$run_dir/engine-$line.pid"
  for _ in $(seq 1 120); do
    if curl -sf "http://localhost:$port/engine/v1/info" > /dev/null; then
      echo "mzTab-M $line engine is up on port $port"
      return 0
    fi
    sleep 1
  done
  echo "The mzTab-M $line engine did not start; see $run_dir/engine-$line.log" >&2
  exit 1
}

start_engine 2.0 "$engine20_port"
engines=("--engines.mztabm[0].line=2.0" "--engines.mztabm[0].url=http://localhost:$engine20_port")
if [[ "$with21" == true ]]; then
  start_engine 2.1 "$engine21_port"
  engines+=("--engines.mztabm[1].line=2.1" "--engines.mztabm[1].url=http://localhost:$engine21_port")
fi

echo "Webapp: http://localhost:$webapp_port/mztabvalidator/"
"$java_cmd" -jar "$(jar_of server)" --server.port="$webapp_port" --server.servlet.contextPath=/mztabvalidator \
  --storage.location="$storage" "${engines[@]}" "${webapp_args[@]+"${webapp_args[@]}"}"
