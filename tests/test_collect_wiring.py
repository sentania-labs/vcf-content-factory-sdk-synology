#!/usr/bin/env python3
"""Build 32 review guards (W1, W3): wiring that needs the framework jar to run.

- W1 (issue #20): collectNfsExport fails an existing export while the NFS
  service is off, before it publishes anything, and the snapshot sets
  nfsDisabled only from NfsProbes.serviceOff.
- W3 (issue #17, DEF-001): both callRaw overloads route IOException and
  RuntimeException through SynologyRedaction.transportFailure, and no
  other catch of the HTTP call exists.
- Round 2 (R13, R14): the Datastore duplicate-row filter runs before the
  count and the index; memory_usage_pct uses SynologyMetrics.memoryUsagePct.
Stdlib only; run from anywhere (ci/run_java_tests.sh runs it).
"""
import pathlib
import re

ROOT = pathlib.Path(__file__).resolve().parent.parent
PKG = ROOT / "src/com/vcfcf/adapters/synology"
ADAPTER = (PKG / "SynologyAdapter.java").read_text()
CLIENT = (PKG / "SynologyApiClient.java").read_text()
STITCHER = (PKG / "SynologyStitcher.java").read_text()


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
    # Round 2 R13 (issue #28, review N3): duplicate rows are skipped before
    # they are counted or indexed.
    load = body(STITCHER, r"public int loadDatastores\(\)")
    dedupe = re.search(r"if \(!DatastoreIndex\.firstSighting\(seen, \"\", e\.name,\s*"
                       r"e\.identifiers\)\)\s*\{\s*continue;\s*\}", load)
    assert dedupe, "loadDatastores does not skip duplicate rows"
    assert dedupe.start() < load.index("total++;") < load.index("index.computeIfAbsent"), \
        "duplicate rows are counted or indexed before the filter"

    # Round 2 R14 (issue #18, review N7): memory_usage_pct goes through the
    # helper that needs a positive total, and only when present.
    assert re.search(r"metricIfPresent\(out, \"Memory\|memory_usage_pct\",\s*"
                     r"SynologyMetrics\.memoryUsagePct\(SJ, mem\)\);", ADAPTER), \
        "memory_usage_pct is not published through memoryUsagePct"
    assert ADAPTER.count("\"Memory|memory_usage_pct\"") == 1, \
        "memory_usage_pct is published somewhere else too"
    # Peer review: Volume cache hit rates and SSD cache total_capacity are
    # published only when DSM sent the field.
    for key, field in (("Cache|cache_read_hit_rate", "hit_rate"),
                       ("Cache|cache_write_hit_rate", "hit_rate_write")):
        assert re.search(r'metricIfPresent\(out, "' + re.escape(key) + r'",\s*'
                         r'SynologyMetrics\.num\(SJ, cache\.get\("' + field + r'"\)\)\);',
                         ADAPTER), f"{key} is not published only when present"
    cache = body(ADAPTER, r"private void collectSsdCache\(")
    assert re.search(r"Double totalBytes = SynologyMetrics\.num\(SJ, size\.get\(\"total\"\)\);"
                     r"\s*if \(totalBytes != null\)\s*\{\s*prop\(out, \"Hardware\|total_capacity\"",
                     cache), "total_capacity is published without a size.total"
    print("test_collect_wiring: all assertions passed")


if __name__ == "__main__":
    main()
