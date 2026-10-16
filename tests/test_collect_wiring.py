#!/usr/bin/env python3
"""Build 32 review guards (W1, W3): wiring that needs the framework jar to run.

- W1 (issue #20): collectNfsExport fails an existing export while the NFS
  service is off, before it publishes anything, and the snapshot sets
  nfsDisabled only from NfsProbes.serviceOff.
- W3 (issue #17, DEF-001): both callRaw overloads route IOException and
  RuntimeException through SynologyRedaction.transportFailure, and no
  other catch of the HTTP call exists.
Stdlib only; run from anywhere (ci/run_java_tests.sh runs it).
"""
import pathlib
import re

ROOT = pathlib.Path(__file__).resolve().parent.parent
PKG = ROOT / "src/com/vcfcf/adapters/synology"
ADAPTER = (PKG / "SynologyAdapter.java").read_text()
CLIENT = (PKG / "SynologyApiClient.java").read_text()


def body(src, sig):
    m = re.search(sig + r"[^{]*\{", src)
    assert m, f"not found: {sig}"
    depth, i = 1, m.end()
    while depth:
        depth += {"{": 1, "}": -1}.get(src[i], 0)
        i += 1
    return src[m.end():i]


def main():
    export = body(ADAPTER, r"private void collectNfsExport\(")
    guard = export.find("SnapshotIsolation.requireNfsService(s.nfsDisabled, name)")
    assert guard >= 0, "collectNfsExport does not fail exports while NFS is off"
    first_pub = min(i for i in (export.find("prop(out"), export.find("metric(out")) if i >= 0)
    assert guard < first_pub, "the NFS-off guard runs after something is published"
    assert re.search(r"if \(NfsProbes\.serviceOff\([^;]*\)\)\s*\{\s*nfsDisabled = true;",
                     ADAPTER, re.S), "nfsDisabled is not set from NfsProbes.serviceOff"

    raw = body(CLIENT, r"private SimpleJson callRaw\(ManagedHttpClient via,")
    assert re.search(r"catch \(IOException \| RuntimeException e\)\s*\{", raw), \
        "callRaw does not catch RuntimeException with IOException"
    assert "throw SynologyRedaction.transportFailure(endpoint, e);" in raw, \
        "callRaw does not rethrow through SynologyRedaction.transportFailure"
    assert raw.count("catch (") == 1, "callRaw has another catch around the HTTP call"
    short = body(CLIENT, r"private SimpleJson callRaw\(String path, String endpoint\)")
    assert "return callRaw(http, path, endpoint);" in short, \
        "the short callRaw overload does not delegate"
    print("test_collect_wiring: all assertions passed")


if __name__ == "__main__":
    main()
