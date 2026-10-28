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
    # Storage: the lists the walk iterated, the disk count it registered,
    # and every record without identity.
    assert enum.count("storageNoId++") == 3
    st = enum.index("read.storage(")
    assert st > enum.index('storage.data().get("disks").asList()')
    assert 'storage.data().get("disks").isList(), disks, storageNoId);' in enum
    assert "read.ssdCaches(ssdCaches.isList(), cachesNoId);" in enum
    assert "read.luns(s.isRead(SnapshotReads.LUNS), lunList.isList(), lunsNoUuid);" in enum
    assert "for (SimpleJson lun : lunList.asList())" in enum
    # NFS: the share list walked, the service state, every failed probe.
    assert "for (SimpleJson share : shareList.asList())" in enum
    assert ("read.exports(s.isRead(SnapshotReads.SHARES), shareList.isList(),\n"
            "\t\t\t\tsharesNoName, s.nfsDisabled, s.nfsProbeFailed.size());") in enum
    assert "read.ups(upsAnswer != null," in enum
    assert '!upsAnswer.get("usb_ups_connect").isNull()' in enum
    # Each read is declared once.
    for call in ("systemHealth", "storage", "ssdCaches", "luns", "exports", "ups"):
        assert enum.count(f"read.{call}(") == 1, call


def snapshot():
    # The UPS answer is recorded only after upsGet returned (a throw leaves null).
    i = SRC.index("SimpleJson ups = api.upsGet();")
    assert SRC.index("s.upsAnswer = ups;", i) < SRC.index("usb_ups_connect", i)
    assert SRC.count("s.upsAnswer = ") == 1


def main():
    hooks()
    enumeration()
    snapshot()
    print("test_vanished_wiring: all assertions passed")


if __name__ == "__main__":
    main()
