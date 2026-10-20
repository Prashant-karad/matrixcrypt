#!/usr/bin/env bash
# Compile and start MatrixCrypt.
#   ./run.sh          start the web app (http://localhost:8080)
#   ./run.sh bench    benchmark in the terminal (saves bench-results.csv)
#   ./run.sh test     self-test
set -e
cd "$(dirname "$0")"
mkdir -p out
javac -d out src/*.java
exec java -Djava.awt.headless=true -cp out Main "$@"
