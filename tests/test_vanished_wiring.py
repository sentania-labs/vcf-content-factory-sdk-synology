#!/usr/bin/env python3
"""Build 35 (buildkit 1.0.22) wiring the SDK-bound adapter cannot be
unit-tested for.

The rule for which kinds are read in full is ReadKinds (unit-tested in
ReadKindsTest). This checks the adapter feeds it from the same walk that
registers the resources, from the snapshot's own read records (unread
endpoints, nfsProbeFailed, nfsDisabled, the UPS answer), and that
kindsReadThisCycle answers only for the enumeration that just ran on this
thread. Stdlib only; ci/run_java_tests.sh runs it.
"""
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
SRC = (ROOT / "src/com/vcfcf/adapters/synology/SynologyAdapter.java").read_text()


def method_body(ret, name):
    m = re.search(r"\b" + re.escape(ret) + r"\s+" + name + r"\s*\([^)]*\)[^{;]*\{", SRC)
    if not m:
        sys.exit(f"FAIL: method {name} not found")
    depth, i = 1, m.end()
    while depth:
        depth += {"{": 1, "}": -1}.get(SRC[i], 0)
        i += 1
    return SRC[m.end():i]


def hooks():
    body = method_body("Set<String>", "kindsReadThisCycle")
    assert "r.thread == Thread.currentThread()" in body, "answers for this thread only"
    assert "ReadKinds.EXCLUDED" in method_body("Set<String>", "notExistingExcludedKinds")
    # Build 35 (devel proof): the World is not excluded; it is counted with
    # the DiskStation, so the pre-build-34 World is marked.
    rk = (ROOT / "src/com/vcfcf/adapters/synology/ReadKinds.java").read_text()
    assert "static final Set<String> EXCLUDED = Collections.emptySet();" in rk
    for name in ("kindsReadThisCycle", "notExistingExcludedKinds"):
        assert re.search(r"@Override\s+protected Set<String> " + name, SRC), name
    # No threshold override: the framework default of 12 cycles stands.
    assert "notExistingMissedCycles" not in SRC.replace(
        "{@code notExistingMissedCycles()}", ""), "threshold left at default"


def enumeration():
    enum = method_body("void", "enumerateResources")
    # Cleared first, recorded last: a throw anywhere leaves nothing declared.
    assert enum.index("lastRead = null;") < enum.index("currentSnapshot()")
    tail = enum.rstrip()[:-1].rstrip()
    assert tail.endswith(
        "lastRead = new ReadRecord(Thread.currentThread(), read.kinds());"), tail[-120:]
    assert enum.count("lastRead = ") == 2
    assert "read.systemHealth(s.isRead(SnapshotReads.SYSTEM_HEALTH));" in enum
    # The current World is registered unconditionally, first, right after
    # the snapshot: whenever the World kind is declared, it was delivered.
    world = ('sink.accept(rcOf("SynologyWorld", s.device.worldName(),\n'
             '\t\t\t\t"world_id", "synology_world", dev));')
    assert enum.count(world) == 1, "World registered once"
    w = enum.index(world)
    assert enum.index("currentSnapshot()") < w, "World after the snapshot"
    assert enum.index("sink.accept(") == w, "World is the first resource registered"
    pre = enum[enum.index("currentSnapshot()"):w]
    assert not re.search(r"\b(if|for|while|return|switch|try)\b", pre), \
        "nothing between the snapshot and the World can skip it"
    # Storage: the lists the walk iterated, the disk count it registered,
    # and every record without identity.
    assert enum.count("storageNoId++") == 3
    st = enum.index("read.storage(")
    assert st > enum.index('storage.data().get("disks").asList()')
    assert ('storage.data().get("disks").isList(), pools, volumes, disks,\n'
            '\t\t\t\tstorageNoId, ReadKinds.noPoolConfigured(diskUsedBy, sharedCacheIds,\n'
            '\t\t\t\t\t\tnoUnassembledPool));') in enum
    # Review R3-B1: missing_pools and detected_pools, both lists and empty,
    # gate the storage kinds and the no-pool evidence alike.
    assert 'SimpleJson missingPools = storage.data().get("missing_pools");' in enum
    assert 'SimpleJson detectedPools = storage.data().get("detected_pools");' in enum
    assert ("int missingCount = missingPools.isList() ? missingPools.asList().size() : 0;"
            in enum)
    assert ("int detectedCount = detectedPools.isList() ? detectedPools.asList().size() : 0;"
            in enum)
    assert ("read.unassembledPools(missingPools.isList(), missingCount,\n"
            "\t\t\t\tdetectedPools.isList(), detectedCount);") in enum
    assert ("boolean noUnassembledPool = ReadKinds.noUnassembledPool(\n"
            "\t\t\t\tmissingPools.isList(), missingCount, detectedPools.isList(),\n"
            "\t\t\t\tdetectedCount);") in enum
    # Review R3-W1: dangling pool_path and cache.id, and is_missing caches.
    assert "if (!cacheIds.contains(volCacheId)) volumeCacheUnlisted = true;" in enum
    assert 'String poolPath = vol.get("pool_path").asString();' in enum
    assert "if (!ReadKinds.noId(poolPath) && !poolIds.contains(poolPath)) {" in enum
    assert 'if (sc.get("is_missing").asBoolean()) sharedCacheMissing = true;' in enum
    assert ("read.contradictions(volumePoolUnlisted, volumeCacheUnlisted,\n"
            "\t\t\t\tsharedCacheMissing);") in enum
    assert enum.index("read.contradictions(") < enum.index("lastRead = new ReadRecord(")
    # Review R4-W1: a disk naming an unlisted pool, and the expansion-unit
    # flags, present-only, from this load_info.
    assert ("read.disksNameUnlistedPool(StorageOverview.diskNamesUnlistedPool(\n"
            "\t\t\t\tdiskUsedBy, poolIds, sharedCacheIds, cacheIds));") in enum
    assert 'SimpleJson overview = storage.data().get("overview_data");' in enum
    assert ("StorageOverview.eunitLost(!eunitLost.isNull(), eunitLost.asString()),"
            in enum)
    assert ("StorageOverview.eunitIncompatible(!eunitIncompatible.isNull(),\n"
            "\t\t\t\t\t\teunitIncompatible.isList(), eunitIncompatible.size()));") in enum
    for call in ("disksNameUnlistedPool", "expansionUnits"):
        assert enum.index(f"read.{call}(") < enum.index("lastRead = new ReadRecord(")
    # Build 35 review: empty lists need evidence, gathered from this load_info.
    assert 'SimpleJson usedBy = disk.get("used_by");' in enum
    assert 'storage.data().get("sharedCaches")' in enum
    assert 'vol.get("cache").get("id")' in enum
    assert ("read.ssdCaches(ssdCaches.isList(), caches, cachesNoId,\n"
            "\t\t\t\tReadKinds.noCacheConfigured(sharedCacheIds, diskUsedBy, poolIds,\n"
            "\t\t\t\t\t\tvolumeCached));") in enum
    assert ("read.luns(s.isRead(SnapshotReads.LUNS), lunList.isList(), luns,\n"
            "\t\t\t\tlunsNoUuid);") in enum
    assert "for (SimpleJson lun : lunList.asList())" in enum
    # NFS: the share list walked, the service state, every failed probe.
    assert "for (SimpleJson share : shareList.asList())" in enum
    assert ("read.exports(s.isRead(SnapshotReads.SHARES), shareList.isList(),\n"
            "\t\t\t\tshareList.isList() ? shareList.asList().size() : 0,\n"
            "\t\t\t\tshareTotal.isNull() ? -1 : (int) shareTotal.asLong(),\n"
            "\t\t\t\tsharesNoName, s.nfsDisabled, s.nfsProbeFailed.size(), exports);") in enum
    # Review R3-N1: the total is the share list's own.
    assert 'SimpleJson shareTotal = s.shares.data().get("total");' in enum
    assert "read.ups(upsAnswer != null," in enum
    assert '!upsAnswer.get("usb_ups_connect").isNull()' in enum
    # Each read is declared once.
    for call in ("systemHealth", "storage", "ssdCaches", "luns", "exports", "ups",
                 "unassembledPools", "contradictions", "disksNameUnlistedPool",
                 "expansionUnits"):
        assert enum.count(f"read.{call}(") == 1, call


def dsm_errors():
    # Build 35 review: a DSM error in a 200 (success false) is never a read.
    # Every data API goes through call(), which throws on it; the UPS answer
    # and the NFS rule probes are data API calls too.
    client = (ROOT / "src/com/vcfcf/adapters/synology/SynologyApiClient.java").read_text()
    m = re.search(r"private SimpleJson call\(String api[^{]*\{", client)
    assert m, "call() not found"
    body = client[m.end():client.index("\n\t}\n", m.end())]
    assert body.count("if (!resp.isSuccess()) {") == 2, "success checked before and after re-login"
    assert "throw new SynologyDsmException(" in body
    for api in ('return call("SYNO.Core.Share", 1, "list",',
                'return call("SYNO.Core.ISCSI.LUN", 1, "list");',
                'return call("SYNO.Core.FileServ.NFS.SharePrivilege", 1, "load",',
                'return call("SYNO.Core.ExternalDevice.UPS", 1, "get");'):
        assert api in client, api
    # A probe answer with no rule list is a failed probe, not zero rules.
    assert ('rules -> rules.data().get("rule").isList()\n'
            '\t\t\t\t\t\t\t\t? rules.data().get("rule").size() : -1,') in SRC


def snapshot():
    # The UPS answer is recorded only after upsGet returned (a throw leaves null).
    i = SRC.index("SimpleJson ups = api.upsGet();")
    assert SRC.index("s.upsAnswer = ups;", i) < SRC.index("usb_ups_connect", i)
    assert SRC.count("s.upsAnswer = ") == 1


def main():
    hooks()
    enumeration()
    dsm_errors()
    snapshot()
    print("test_vanished_wiring: all assertions passed")


if __name__ == "__main__":
    main()
