#!/usr/bin/env python3
"""Build 35: the navigation spec is rooted at the World kind.

Up to build 34 it was rooted at the adapter instance kind, whose resource
has no children, so the tree in VCF Operations stopped at the instance
(devel and devel_9_1, 2026-10-28). VMWARE roots its own spec at
"vSphere World" and starts every path there. Stdlib only; run from
anywhere.
"""
import pathlib
import xml.etree.ElementTree as ET

ROOT = pathlib.Path(__file__).resolve().parent.parent
PREFIX = ("synology_diskstation::SynologyWorld||"
          "synology_diskstation::SynologyDiskstation::child||")


def main():
    root = ET.parse(ROOT / "describe.xml").getroot()
    specs = [e for e in root.iter() if e.tag.endswith("TraversalSpecKind")]
    assert len(specs) == 1, f"{len(specs)} TraversalSpecKinds"
    spec = specs[0]
    assert spec.get("rootAdapterKind") == "synology_diskstation"
    assert spec.get("rootResourceKind") == "SynologyWorld", spec.get("rootResourceKind")
    paths = [e.get("path") for e in spec.iter() if e.tag.endswith("ResourcePath")]
    assert len(paths) == 7, f"{len(paths)} ResourcePaths"
    for p in paths:
        assert p.startswith(PREFIX), p
    print("test_traversal_root: all assertions passed")


def test_traversal_root():
    main()


if __name__ == "__main__":
    main()
