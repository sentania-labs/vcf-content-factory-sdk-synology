# Installing & Configuring — VCF Content Factory Synology DiskStation

## Prerequisites

- VCF Operations 8.x or 9.x (collect-path discovery is validated on
  9.0.2).
- A Synology DiskStation running **DSM 7.2 or later**.
- Network reachability from the VCF Operations collector to the NAS on
  **TCP 5001** (DSM HTTPS Web API).
- A **DSM account in the administrators group**, without 2-step
  verification (see Permissions Required below).

## Permissions Required

The adapter only reads; it performs no writes.

**Minimum privileges (build 31+).** Four DSM Web API endpoints are
required. If any of them is refused, the snapshot fails and nothing is
discovered or collected for that cycle:

| Required endpoint | Carries |
|---|---|
| `SYNO.DSM.Info` | DiskStation model, serial, temperature, uptime |
| `SYNO.Core.System` (info) | hostname, firmware |
| `SYNO.Core.System.Utilization` | CPU, memory, network, disk / volume / LUN IO |
| `SYNO.Storage.CGI.Storage` (load_info) | storage pools, volumes, disks, SSD caches |

These are the endpoints behind DSM's Info Center, Resource Monitor and
Storage Manager, which DSM offers to members of the administrators group,
so use an account in that group. A non-administrator account has not been
tested with this adapter. The account must not require 2-step
verification: the adapter cannot answer a one-time code, so DSM refuses
the login (error 403 or 406) and the adapter backs off as for a wrong
password.

Every other endpoint is optional. When one is refused or unavailable (a
model without fans, Virtual DSM, a service that is not installed, a
missing privilege), the adapter logs one WARN naming the endpoint, keeps
collecting everything else, and skips only what depends on it, never
publishing a placeholder value:

| Optional endpoint | Skipped while it is not readable |
|---|---|
| `SYNO.Core.Hardware.FanSpeed` | DiskStation `Fan` properties |
| `SYNO.Core.FileServ.NFS` | DiskStation `NFS` service enabled properties |
| `SYNO.Core.ISCSI.LUN` | iSCSI LUN objects (existing ones go ERROR), their Volume and Datastore relationships |
| `SYNO.Core.ISCSI.Target` | iSCSI LUN target IQN, enabled and portal properties |
| `SYNO.Core.Network.Interface` | DiskStation NIC count, the NFS export Datastore cross-link |
| `SYNO.Core.CurrentConnection` | NFS client counts (DiskStation and NFS export) |
| `SYNO.Core.Share` | NFS export objects (existing ones go ERROR), their Volume and Datastore relationships |
| `SYNO.Core.FileServ.NFS.SharePrivilege` (per share) | that share as an NFS export |
| `SYNO.Core.ExternalDevice.UPS` | the UPS object |

The UPS read and the per-share NFS rule probe keep their build 30
logging: an INFO each cycle for UPS, a WARN each cycle per share whose
probe fails.

While the iSCSI LUN list or the share list cannot be read, no Volume
relationships are refreshed (a Volume's LUN, NFS export and SSD cache
edges stay as they were), so a temporary failure does not detach them.

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
- **The adapter log has `Optional DSM endpoint ... is not readable`**
  (build 31+). That endpoint was refused or is not offered by this model
  or DSM edition; see the optional endpoint table above for what is
  skipped. The WARN is logged once per instance configuration, and an INFO
  is logged when the endpoint becomes readable again.
- **A resource shows ERROR with no metrics** — DSM returned a
  success-shaped but empty payload for a critical endpoint; the adapter
  surfaces this as ERROR rather than publishing `0.0` sentinels. Verify the
  DSM account can read system and utilization info.
- **Every Synology object is ERROR after a password change** (build 31+).
  The adapter log has one WARN `Synology snapshot build failed: Synology
  login failed: error code 400 ...`. After a DSM login refusal the adapter
  does not log in again for 15 minutes (30, then 60 minutes if the
  refusals continue), to keep DSM Auto Block from blocking the collector's
  IP. Edit the adapter instance with the new password: the edit takes
  effect on the next cycle without waiting out the back-off.
- **The adapter log names DSM login error 407** (source IP blocked). DSM
  Auto Block has already blocked the collector's IP, usually after earlier
  failed logins from this or another integration. Clear the block in DSM
  (Control Panel > Security > Protection, Auto Block, Allow/Block List), fix the
  password if needed, then edit the adapter instance.
- **No Datastore cross-link appears** — expected on a collector without
  ambient Suite API access, or when no VMware datastore is backed by this
  NAS. Storage collection is unaffected.
