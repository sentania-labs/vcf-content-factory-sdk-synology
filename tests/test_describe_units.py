#!/usr/bin/env python3
"""Build 32: the declared units and display names this build changed.

Pins describe.xml units and resources.properties names per key, so a later
edit cannot silently restore a wrong unit. Stdlib only; run from anywhere
(ci/run_java_tests.sh runs it).
"""
import pathlib
import re
import xml.etree.ElementTree as ET

ROOT = pathlib.Path(__file__).resolve().parent.parent
TREE = ET.parse(ROOT / "describe.xml")
NAMES = {}
for line in (ROOT / "resources/resources.properties").read_text().splitlines():
    m = re.match(r"^(\d+)=(.*)$", line)
    if m:
        NAMES[m.group(1)] = m.group(2)


def attr(kind, key):
    for rk in TREE.getroot().iter():
        if rk.tag.endswith("ResourceKind") and rk.get("key") == kind:
            for a in rk.iter():
                if a.tag.endswith("ResourceAttribute") and a.get("key") == key:
                    return a
    raise AssertionError(f"{kind}.{key} not declared")


# kind, key, unit (None = no unit attribute), display name
EXPECT = [
    # issue #24: load averages are unitless
    ("SynologyDiskstation", "cpu_load_1m", None, "Load Average (1 min)"),
    ("SynologyDiskstation", "cpu_load_5m", None, "Load Average (5 min)"),
    ("SynologyDiskstation", "cpu_load_15m", None, "Load Average (15 min)"),
    # issue #18: memory stays declared in bytes (values now x1024)
    ("SynologyDiskstation", "memory_total", "bytes", "Memory Total"),
    ("SynologyDiskstation", "memory_available", "bytes", "Memory Available"),
]


def main():
    for kind, key, unit, name in EXPECT:
        a = attr(kind, key)
        assert a.get("unit") == unit, f"{kind}.{key}: unit {a.get('unit')!r}, want {unit!r}"
        got = NAMES.get(a.get("nameKey"))
        assert got == name, f"{kind}.{key}: name {got!r}, want {name!r}"
    print("test_describe_units: all assertions passed")


if __name__ == "__main__":
    main()
