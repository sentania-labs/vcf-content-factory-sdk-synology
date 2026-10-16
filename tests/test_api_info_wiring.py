#!/usr/bin/env python3
"""Build 32, issue #31: SYNO.API.Info is asked at login and never fails it.

DsmApisTest proves the version pick; this pins the wiring in
SynologyApiClient, which needs the framework jar to run: the first login
loads SYNO.API.Info, every URL goes through DsmApis.pick, a failure other
than an unreachable NAS falls back with one WARN, and no hardcoded
"/webapi/entry.cgi" URL is left. Stdlib only.
"""
import pathlib
import re

ROOT = pathlib.Path(__file__).resolve().parent.parent
SRC = (ROOT / "src/com/vcfcf/adapters/synology/SynologyApiClient.java").read_text()


def body(sig):
    m = re.search(sig + r"[^{]*\{", SRC)
    assert m, f"not found: {sig}"
    depth, i = 1, m.end()
    while depth:
        depth += {"{": 1, "}": -1}.get(SRC[i], 0)
        i += 1
    return SRC[m.end():i]


def main():
    login = body(r"private String sendLogin\(\)")
    assert re.search(r"if \(!apiInfoAsked\)\s*\{\s*loadApiInfo\(\);", login), \
        "the login does not load SYNO.API.Info first (once per client)"
    info = body(r"private void loadApiInfo\(\)")
    assert "catch (InterruptedException ie)" in info and "throw ie;" in info
    assert "SynologyFailures.isUnreachable(e)" in info, "unreachable is not rethrown"
    # Review N6: answered once per client, fallback included; an
    # unreachable NAS is rethrown before the flag is set.
    unreach = info.index("SynologyFailures.isUnreachable(e)")
    assert info.count("apiInfoAsked = true;") == 2, "the asked flag is not set on both paths"
    assert info.index("apiInfoAsked = true;") > unreach, \
        "an unreachable NAS marks SYNO.API.Info as asked"
    # Review W4: a lowered version is a WARN.
    assert re.search(r"if \(!found\.lowered\.isEmpty\(\)\)\s*\{\s*log\.warn\(", info), \
        "a lowered API version is not logged at WARN"
    assert re.search(r"\breturn;\s*\}", info), "a failed SYNO.API.Info must return, not throw"
    assert '"/webapi/entry.cgi' not in SRC, "a hardcoded entry.cgi URL is left"
    assert "apis.pick(api, version)" in SRC, "data calls do not use the pick"
    assert "apis.pick(DsmApis.AUTH, 7)" in SRC, "logout does not use the pick"
    print("test_api_info_wiring: all assertions passed")


if __name__ == "__main__":
    main()
