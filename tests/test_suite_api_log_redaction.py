#!/usr/bin/env python3
"""Build 32, issue #27: Suite API log lines carry no account and no raw message.

The explicit Suite API path INFO names the host only, and every WARN that
appends a Suite API exception message passes it through
SynologyRedaction.redact first. Stdlib only; run from anywhere
(ci/run_java_tests.sh runs it).
"""
import pathlib
import re

ROOT = pathlib.Path(__file__).resolve().parent.parent
PKG = ROOT / "src/com/vcfcf/adapters/synology"
ADAPTER = (PKG / "SynologyAdapter.java").read_text()
STITCHER = (PKG / "SynologyStitcher.java").read_text()


def log_calls(src):
    """Every logInfo/logWarn/logger.* call with its full argument text."""
    out = []
    for m in re.finditer(r"\b(logInfo|logWarn|logger\.(?:info|warn|debug))\(", src):
        depth, i = 1, m.end()
        while depth:
            depth += {"(": 1, ")": -1}.get(src[i], 0)
            i += 1
        out.append(src[m.start():i])
    return out


def main():
    for call in log_calls(ADAPTER) + log_calls(STITCHER):
        assert "suiteApiUser" not in call, f"Suite API account logged: {call}"
        assert "suiteApiPassword" not in call, f"Suite API password logged: {call}"
    for src, marker in (
            (ADAPTER, "Datastore cross-link skipped"),
            (ADAPTER, "Datastore cross-link failed"),
            (STITCHER, "VMWARE Datastore load failed")):
        calls = [c for c in log_calls(src) if marker in c]
        assert calls, f"log line not found: {marker}"
        for c in calls:
            assert "getMessage()" in c, f"{marker}: no message appended"
            assert re.search(r"SynologyRedaction\.redact\(\s*\w+\.getMessage\(\)\)", c), \
                f"{marker}: message not redacted: {c}"
    print("test_suite_api_log_redaction: all assertions passed")


if __name__ == "__main__":
    main()
