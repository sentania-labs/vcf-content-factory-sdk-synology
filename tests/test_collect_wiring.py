#!/usr/bin/env python3
"""Build 32 review guards (W1, W3): wiring that needs the framework jar to run.

- W1 (issue #20): collectNfsExport fails an existing export while the NFS
  service is off, before it publishes anything, and the snapshot sets
  nfsDisabled only from NfsProbes.serviceOff.
- Build 35 (issue #16): the login and every authenticated call are form
  POSTs; only SYNO.API.Info is a GET.
- W3 (issue #17, DEF-001): both callRaw overloads route IOException and
  RuntimeException through SynologyRedaction.transportFailure, and no
  other catch of the HTTP call exists.
- Build 35 review W1, B2: pool, volume, cache, SMART and UPS statuses are
  present-only; Fan|fan_health comes from SystemHealth, gated on its read.
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
    short = body(CLIENT, r"private SimpleJson callRaw\(DsmRequest req, String endpoint\)")
    assert "return callRaw(http, req, endpoint);" in short, \
        "the short callRaw overload does not delegate"
    # Build 35 (issue #16): a request with a body is a form POST; only the
    # bodiless SYNO.API.Info request is a GET, and no URL carries _sid,
    # account or passwd.
    assert re.search(r"req\.body == null\s*\?\s*via\.get\(req\.path,", raw) and \
        re.search(r"via\.post\(req\.path, req\.body, DsmRequest\.FORM,", raw), \
        "callRaw does not POST the form body"
    assert CLIENT.count("DsmRequest.get(") == 1 and \
        "DsmRequest.get(DsmApis.INFO_PATH)" in CLIENT, \
        "a GET other than SYNO.API.Info is back"
    assert "synoUrl" not in CLIENT and "&_sid=" not in CLIENT and "?api=" not in CLIENT, \
        "a query-string request builder is back in the client"
    login = body(CLIENT, r"private String sendLogin\(\)")
    assert "dsmRequest(DsmApis.AUTH, 7, \"login\"" in login, "login is not a form POST"
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
    # Build 33 review round 2: count the bare key with its closing quote, so
    # a second publish that builds the key from two strings is caught too.
    assert ADAPTER.count('memory_usage_pct"') == 1, \
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
    # Build 33 review W1 (issue #41): each load average goes through the
    # helper that divides by 100 once, from its own field, and only there.
    for key, field in (("CPU|cpu_load_1m", "1min_load"),
                       ("CPU|cpu_load_5m", "5min_load"),
                       ("CPU|cpu_load_15m", "15min_load")):
        assert re.search(r'metricIfPresent\(out, "' + re.escape(key) + r'",\s*'
                         r'SynologyMetrics\.loadAverage\(SJ, cpu, "' + field + r'"\)\);',
                         ADAPTER), f"{key} is not published through loadAverage from {field}"
        assert ADAPTER.count(key.split("|", 1)[1] + '"') == 1, \
            f"{key} is published somewhere else too"
    # Build 35 review W1: the statuses the bundled symptoms read are
    # present-only (skipped when DSM omits them or sends them blank), never
    # published as "".
    for fn, key, field in (
            (r"private void collectStoragePool\(", "Configuration|status", "pool"),
            (r"private void collectVolume\(", "Configuration|status", "vol"),
            (r"private void collectVolume\(", "Cache|cache_status", "cache"),
            (r"private void collectDisk\(", "Health|smart_status", "disk"),
            (r"private void collectSsdCache\(", "Configuration|status", "cache"),
            (r"private void collectUps\(", "Status|status", "ups")):
        b = body(ADAPTER, fn)
        assert re.search(r'textIfPresent\(out, "' + re.escape(key) + r'", ', b), \
            f"{fn}: {key} is not present-only"
        assert not re.search(r'prop\(out, "' + re.escape(key) + r'"', b), \
            f"{fn}: {key} is still published with prop()"
    # Build 35 review B2: fan health comes from DSM's System Health rule,
    # only when that endpoint was read.
    ds = body(ADAPTER, r"private void collectDiskstation\(")
    assert re.search(r'if \(s\.isRead\(SnapshotReads\.SYSTEM_HEALTH\)\)', ds), \
        "system health is not gated on its read"
    assert 'propIfPresent(out, "Fan|fan_health", SystemHealth.fanHealth(SJ, rule))' in ds, \
        "Fan|fan_health is not SystemHealth.fanHealth"
    # Build 35 review W4: the SSD cache failed-disk count feeds the SSD
    # Cache Disk Failure symptom, so a count DSM omits is skipped, not "0".
    sc = body(ADAPTER, r"private void collectSsdCache\(")
    assert re.search(r'propIfPresent\(out, "Hardware\|disk_failure_count",\s*'
                     r'SynologyMetrics\.count\(SJ, cache\.get\("disk_failure_number"\)\)\);',
                     sc), "Hardware|disk_failure_count is not present-only"
    assert ADAPTER.count('"Hardware|disk_failure_count"') == 1, \
        "Hardware|disk_failure_count is published somewhere else too"
    print("test_collect_wiring: all assertions passed")


if __name__ == "__main__":
    main()
