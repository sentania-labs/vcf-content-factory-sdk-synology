#!/usr/bin/env python3
"""Build 34 (issue #11): every object below the DiskStation is keyed by it.

Pins that describe.xml declares the identifying ``diskstation_id`` on the
World and every kind below the DiskStation (and not on the DiskStation,
whose ``serial`` is that identity), and that the adapter builds every key
through ``resourceKey`` with the snapshot's identity: every ``rcOf`` call
passes it, nothing uses the builder's one-identifier ``rb.resource``, and
collect and the relationship anchor check it. Stdlib only; run from
anywhere (ci/run_java_tests.sh runs it).
"""
import pathlib
import re
import xml.etree.ElementTree as ET

ROOT = pathlib.Path(__file__).resolve().parent.parent
TREE = ET.parse(ROOT / "describe.xml")
SRC = (ROOT / "src/com/vcfcf/adapters/synology/SynologyAdapter.java").read_text()

CHILD_KINDS = ["SynologyWorld", "SynologyStoragePool", "SynologyVolume",
               "SynologyDisk", "SynologyIscsiLun", "SynologyNfsExport",
               "SynologyUps", "SynologySsdCache"]


def identifiers(kind):
    for rk in TREE.getroot().iter():
        if rk.tag.endswith("ResourceKind") and rk.get("key") == kind:
            return {i.get("key"): i for i in rk
                    if i.tag.endswith("ResourceIdentifier")}
    raise AssertionError(f"{kind} not declared")


for kind in CHILD_KINDS:
    ids = identifiers(kind)
    assert "diskstation_id" in ids, f"{kind} lacks diskstation_id"
    assert ids["diskstation_id"].get("identType") in (None, "1"), \
        f"{kind}.diskstation_id must be identifying"
    assert len(ids) == 2, f"{kind}: own id + diskstation_id, got {list(ids)}"
ds = identifiers("SynologyDiskstation")
assert list(ds) == ["serial"], f"DiskStation keeps serial only, got {list(ds)}"

assert "rb.resource(" not in SRC, "keys go through resourceKey, not rb.resource"
calls = re.findall(r"sink\.accept\(rcOf\((.*?)\)\);", SRC, re.S)
assert len(calls) == 9, f"expected 9 rcOf calls, got {len(calls)}"
for c in calls:
    last = c.rsplit(",", 1)[1].strip()
    assert last in ("dev", "null"), f"rcOf must pass the identity: {c!r}"
assert SRC.count('"serial", dev, null') == 1, "only the DiskStation passes null"
assert "DeviceIdentity.belongs(carried, snap.device)" in SRC, \
    "collect checks the object's identity"
assert re.search(r"belongs\(getIdentifier\(rc,\s*DeviceIdentity.ID_KEY\), snap.device\)",
                 SRC), "the World anchor checks its identity"
assert 'asString("unknown")' not in SRC.split("protected void enumerateResources")[1].split("rcOf(String")[0], \
    "no 'unknown' serial sentinel in enumeration"

# Review W2: the Datastore cross-link keys (LUN and NFS export) carry the
# identity, so they bind the current object and never a retired pre-34 one.
for kind in ("SynologyIscsiLun", "SynologyNfsExport"):
    m = re.search(r'resourceKey\("' + kind + r'",(.*?)\);', SRC, re.S)
    assert m, f"no cross-link resourceKey call for {kind}"
    last = m.group(1).rsplit(",", 1)[1].strip()
    assert last == "s.device.value", \
        f"{kind} cross-link key must pass s.device.value, got {last!r}"

# Review B1: the snapshot resolves the identity with the platform's list of
# this instance's DiskStation serials, so a restart cannot re-key the tree.
assert re.search(r"identity\.resolve\(\s*s\.dsmInfo\.data\(\)\.get\(\"serial\"\)"
                 r"\.asString\(\),\s*adapter::existingDiskstationSerials,"
                 r"\s*adapter::logWarn\)", SRC), \
    "Snapshot.build resolves with adapter::existingDiskstationSerials"
body = SRC.split("List<String> existingDiskstationSerials()")[1].split("\n\t}\n")[0]
assert "getAllMonitoringResources()" in body, "reads the platform's objects"
assert "DeviceIdentity.DISKSTATION_KIND.equals(rc.getResourceKind())" in body
assert 'getIdentifier(rc, "serial")' in body

print("test_device_identity_wiring: all assertions passed")
