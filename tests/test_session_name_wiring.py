#!/usr/bin/env python3
"""Build 32, issue #23: the DSM session name is per client, and logout sends _sid.

SessionNameTest proves the names; this pins the wiring, which needs the
framework jar to run: no shared constant remains, configure passes the
instance id, Test Connection passes test=true, and the logout URL is built
with this client's sid and its own session name. Stdlib only.
"""
import pathlib
import re

ROOT = pathlib.Path(__file__).resolve().parent.parent
PKG = ROOT / "src/com/vcfcf/adapters/synology"
CLIENT = (PKG / "SynologyApiClient.java").read_text()
ADAPTER = (PKG / "SynologyAdapter.java").read_text()


def main():
    assert "SESSION_NAME" not in CLIENT, "the shared session-name constant is back"
    assert '"VCFContentFactory"' not in CLIENT + ADAPTER, "a literal shared name is back"
    assert re.search(r'"session="\s*\+\s*urlEncode\(sessionName\)', CLIENT), \
        "login or logout does not send this client's session name"
    m = re.search(r"private void sendLogout\(String sid\)\s*\{(.*?)\n\t\}", CLIENT, re.S)
    assert m, "sendLogout not found"
    body = m.group(1)
    assert re.search(r"synoUrl\(sid,", body), "logout does not pass this client's _sid"
    assert "urlEncode(sessionName)" in body, "logout does not name this client's session"
    assert re.search(r"sessionName\(instanceId,\s*false,", ADAPTER), \
        "configure does not name the session after the instance"
    assert re.search(r"sessionName\(null,\s*true,", ADAPTER), \
        "Test Connection does not use a test session name"
    print("test_session_name_wiring: all assertions passed")


if __name__ == "__main__":
    main()
