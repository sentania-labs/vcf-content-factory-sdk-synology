# Installing & Configuring — VCF Content Factory Synology DiskStation

## Prerequisites

- VCF Operations 8.x or 9.x (collect-path discovery is validated on
  9.0.2).
- A Synology DiskStation running **DSM 7.2 or later**.
- Network reachability from the VCF Operations collector to the NAS on
  **TCP 5001** (DSM HTTPS Web API).
- A **DSM account with API / admin access**. The DSM Web API authenticates
  with an account that can read system, storage, iSCSI, NFS, and UPS
  information.

## Permissions Required

The DSM account needs read access to the DSM Web API endpoints the adapter
queries: system info and utilization, storage pools / volumes / disks,
iSCSI LUN listing and IO, NFS share listing and rules, SSD cache, and UPS.
An administrator account satisfies this; if you scope a dedicated account,
ensure it can read these areas via the DSM API. The adapter only reads — it
performs no writes.

> **Session note:** the adapter manages its own DSM session. It logs in to
> obtain a `_sid` session token, passes `_sid` on every Web API call, and
> re-authenticates automatically when DSM reports the session expired (DSM
> error 106 / 107 / 119). The `_sid` value is redacted from all logs and
> error messages.

## Network Requirements

| Port | Protocol | Direction | Purpose |
|------|----------|-----------|---------|
| 5001 | HTTPS    | Collector → DiskStation | DSM Web API (system, storage, iSCSI, NFS, UPS reads) |

The optional Datastore cross-link uses the **ambient** local Suite API on
the collector and requires no additional outbound network configuration.

## TLS — certificate trust

DSM ships a self-signed certificate on port 5001. By default
(`allowInsecure` is `false`) the adapter validates the NAS certificate
chain through the VCF Operations platform trust manager. This build does
not offer the NAS certificate for review in Validate Connection, so an
untrusted certificate (DSM's factory self-signed one, for example) fails
Test Connection with a message that says the certificate is not trusted.
Import the NAS certificate (or the CA that signed it) into the platform
trust store, or replace the DSM certificate with one the platform already
trusts.

Setting **`allowInsecure=true`** turns certificate validation off, so the
DSM credentials and session id go to whatever answers at that address.
Use it only where you accept that, for example a lab NAS with its factory
self-signed certificate. While it is on, the adapter logs a WARN each time
the instance is configured.

Upgrading from build 30 or earlier: existing instances keep their stored
`allowInsecure` value. An instance created with the old default stores
`true` and keeps skipping validation until you edit it to `false`. Only
the exact value `true` (any letter case) turns validation off. This is
stricter than build 30, which skipped validation for every value except
`false`: an existing instance whose stored value is blank, `yes`, `1` or
any other text now validates the NAS certificate, and if that certificate
is untrusted its collection fails with a certificate message until you
set `allowInsecure=true` or import the certificate.

## Configuration Fields

When adding a new adapter instance in VCF Operations, you will be prompted
for:

| Field | Key | Required | Default | Notes |
|-------|-----|----------|---------|-------|
| Host / IP Address | `host` | Yes | — | NAS hostname or IP. |
| Port (HTTPS, default 5001) | `port` | No | 5001 | DSM HTTPS Web API port. |
| Allow Insecure SSL (true/false) | `allowInsecure` | No | false | `true` turns off certificate validation for the NAS (lab use only). Any other value validates. |
| Username | `username` | Yes | — | DSM account with API access. |
| Password | `password` | Yes | — | DSM account password (masked). |

## Step-by-Step Installation

1. Install the `.pak` file via **Administration > Solutions > Add**.
2. After installation, navigate to **Data Sources > Integrations > Accounts**.
3. Click **Add Account** and select **VCF Content Factory Synology DiskStation**.
4. Fill in the configuration fields above (host, port `5001`, DSM
   credentials; leave `allowInsecure` at `false` unless the NAS
   certificate is untrusted and you accept the lab-only opt-out described
   under TLS above).
5. Click **Validate Connection**, then **Add**.
6. The adapter discovers the storage tree and begins collecting on the
   next cycle (default 5 minutes).

## Troubleshooting

- **Test Connection fails on TLS**: the NAS certificate is untrusted
  (see TLS above). Import the certificate, or set `allowInsecure=true`
  only if you accept an unvalidated connection.
- **A resource shows ERROR with no metrics** — DSM returned a
  success-shaped but empty payload for a critical endpoint; the adapter
  surfaces this as ERROR rather than publishing `0.0` sentinels. Verify the
  DSM account can read system and utilization info.
- **No Datastore cross-link appears** — expected on a collector without
  ambient Suite API access, or when no VMware datastore is backed by this
  NAS. Storage collection is unaffected.
