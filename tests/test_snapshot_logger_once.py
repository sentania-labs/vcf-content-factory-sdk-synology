#!/usr/bin/env python3
"""Factory issue #224: the snapshot cache's logger is resolved once.

The framework's componentLogger() resets the logger to INFO on every call,
so a per-failure componentLogger(SnapshotCache.class) would make the cache's DEBUG
line unreachable. The adapter must resolve it once in configureAdapter,
store it in the Configured holder and hand its debug method to the cache.
Stdlib only; run from anywhere (ci/run_java_tests.sh runs it).
"""
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
SRC = (ROOT / "src/com/vcfcf/adapters/synology/SynologyAdapter.java").read_text()


def method_body(ret, name):
    m = re.search(r"\b" + ret + r"\s+" + name + r"\s*\([^)]*\)[^{;]*\{", SRC)
    if not m:
        sys.exit(f"FAIL: method {name} not found")
    depth, i = 1, m.end()
    while depth:
        depth += {"{": 1, "}": -1}.get(SRC[i], 0)
        i += 1
    return SRC[m.end():i]


def main():
    calls = SRC.count("componentLogger(SnapshotCache.class)")
    assert calls == 1, f"componentLogger(SnapshotCache.class) appears {calls} times"
    configure = method_body("void", "configureAdapter")
    assert "componentLogger(SnapshotCache.class)" in configure, \
        "the snapshot logger is not resolved in configureAdapter"
    assert re.search(r"new Configured\([^;]*snapshotLog,", configure, re.S), \
        "the resolved logger is not stored in the Configured holder"
    assert "snapshotLog::debug" in configure, \
        "the cache is not given the stored logger's debug method"
    current = method_body("Snapshot", "currentSnapshot")
    assert "componentLogger" not in current, \
        "currentSnapshot resolves a logger per call"
    print("test_snapshot_logger_once: all assertions passed")


if __name__ == "__main__":
    main()
