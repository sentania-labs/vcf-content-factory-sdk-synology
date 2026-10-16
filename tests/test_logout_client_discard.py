#!/usr/bin/env python3
"""Codex P2: the dedicated DSM logout client is discarded on close.

Every configure builds a single-attempt logout ManagedHttpClient; before
this fix SynologyApiClient.close() logged the session out and never
discarded it, and super.onDiscard() manages only the main client, so each
reconfigure or removal leaked one client. DsmSessionTest covers the release
step's timing (after the logout, with or without a session, when the logout
throws, once); this pins the wiring, which needs the framework jar to run:
the API client hands DsmSession its logout client's discard, and never the
main client's (the tester passes one client for both). Stdlib only.
"""
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
SRC = (ROOT / "src/com/vcfcf/adapters/synology/SynologyApiClient.java").read_text()


def main():
    m = re.search(r"new DsmSession\(([^;]*)\);", SRC, re.S)
    if not m:
        sys.exit("FAIL: no DsmSession construction in SynologyApiClient")
    args = m.group(1)
    assert "logoutHttp::discard" in args, \
        "DsmSession is not given the logout client's discard"
    assert "logoutHttp != http" in args, \
        "the main client could be discarded when it is also the logout client"
    assert "http::discard" not in args.replace("logoutHttp::discard", ""), \
        "the main client's discard is wired"
    print("test_logout_client_discard: all assertions passed")


if __name__ == "__main__":
    main()
