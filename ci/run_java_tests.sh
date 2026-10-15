#!/usr/bin/env bash
# Compiles and runs the adapter's plain-main Java tests (no test framework,
# no external deps beyond a JDK), then the Python checks (stdlib only).
# Exits non-zero on any compile error or failed assertion. Run from
# anywhere:
#   ci/run_java_tests.sh
# The tested classes have no VCF Ops SDK or framework-jar dependency, so
# neither jar is needed. Added in build 31; the tag workflow does not run
# it yet.
set -euo pipefail
cd "$(dirname "$0")/.."
out="$(mktemp -d)"
trap 'rm -rf "$out"' EXIT
javac -d "$out" -sourcepath src:tests \
    tests/com/vcfcf/adapters/synology/*.java
for t in AllowInsecureTest; do
    java -cp "$out" "com.vcfcf.adapters.synology.$t"
done
python3 tests/test_allow_insecure_default.py
