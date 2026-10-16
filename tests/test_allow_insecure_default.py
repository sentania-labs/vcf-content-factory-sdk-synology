#!/usr/bin/env python3
"""Build 31 (issue #15): Allow Insecure SSL defaults to false.

describe.xml is what a new adapter instance is created with; README.md,
REFERENCE.md and docs/installing.md must state the same default. Before
build 31 describe.xml and REFERENCE.md said true, README said to use true
for self-signed certificates, and docs/installing.md said false. Stdlib
only. Run from anywhere: python3 tests/test_allow_insecure_default.py
"""
import os
import re
import sys
import xml.etree.ElementTree as ET

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))


def describe_default():
    tree = ET.parse(os.path.join(ROOT, "describe.xml"))
    found = []
    for el in tree.iter():
        if el.tag.split("}")[-1] == "ResourceIdentifier" and \
                el.get("key") == "allowInsecure":
            found.append(el.get("default"))
    assert len(found) == 1, "one allowInsecure identifier, found %r" % found
    return found[0]


def table_rows(rel):
    """Cells of every table row naming Allow Insecure SSL."""
    out = []
    with open(os.path.join(ROOT, rel), encoding="utf-8") as f:
        for line in f:
            if line.startswith("| Allow Insecure SSL"):
                cells = [c.strip() for c in line.strip().strip("|").split("|")]
                out.append(cells)
    assert out, "%s has an Allow Insecure SSL row" % rel
    return out


def read(rel):
    with open(os.path.join(ROOT, rel), encoding="utf-8") as f:
        return f.read()


def main():
    failures = []
    d = describe_default()
    if d != "false":
        failures.append("describe.xml allowInsecure default is %r, want 'false'" % d)

    # README: a bullet "- **Allow Insecure SSL**: default `false` ..."
    bullets = [l for l in read("README.md").splitlines()
               if "**Allow Insecure SSL**" in l]
    if not bullets:
        failures.append("README.md has no Allow Insecure SSL bullet")
    for b in bullets:
        if "default `false`" not in b:
            failures.append("README.md bullet does not state default false: %r" % b)
    # REFERENCE.md: | Field | Key | Default | Required |
    for cells in table_rows("REFERENCE.md"):
        if cells[2] != "false":
            failures.append("REFERENCE.md default %r" % cells[2])
    # docs/installing.md: | Field | Key | Required | Default | Notes |
    for cells in table_rows("docs/installing.md"):
        if cells[3] != "false":
            failures.append("docs/installing.md default %r" % cells[3])

    text = read("docs/installing.md")
    if not re.search(r"existing instances keep their stored", text, re.I):
        failures.append("docs/installing.md has no upgrade note")
    if not re.search(r"blank, `yes`, `1`", text):
        failures.append("docs/installing.md does not say a blank or other "
                        "stored value now validates")

    if failures:
        for f in failures:
            print("FAIL: " + f)
        sys.exit(1)
    print("test_allow_insecure_default: all assertions passed")


if __name__ == "__main__":
    main()
