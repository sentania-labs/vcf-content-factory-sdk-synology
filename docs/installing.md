# Installing & Configuring — VCF Content Factory Synology DiskStation

## Prerequisites

- VCF Operations 8.x or 9.x (collect-path discovery is validated on
  9.0.2).
- A Synology DiskStation running **DSM 7.2 or later** (developed and
  tested against DSM 7.3.2). At its first login the adapter asks DSM
  (`SYNO.API.Info`) which version of each Web API it offers and uses the
  lower of that and the version the adapter was written for, so a DSM
  that offers only older versions is called with those; DSM 6.x and
  DSM 7.0 to 7.1 are not tested. An older API version can return an
  older payload without some fields the adapter reads; such a field is
  skipped or, for some keys, reads 0, and the adapter log names the APIs
  in use at an older version with one WARN. If DSM does not list a
  required API, or offers it only at versions newer than the adapter's,
  the adapter log says so once. If `SYNO.API.Info` cannot be read, the adapter
  uses its built-in versions (one WARN) and the login goes ahead.
- Network reachability from the VCF Operations collector to the NAS on
  **TCP 5001** (DSM HTTPS Web API).
- A **dedicated DSM account in the administrators group**, used only by
  this adapter, without 2-step verification (see Permissions Required
  below).

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
the login (error 403, 404 or 406) and the adapter backs off as for a wrong
password. The error says so: "2-step verification code required: this
adapter does not support 2-step verification; use a dedicated DSM account
without it".

**Hardening the service account.** Because it cannot use 2-step
verification, give the adapter its own account rather than a person's:

- Create a dedicated DSM user (for example `svc-vcfops`) for this adapter
  only, with a long random password, and do not use it to sign in to DSM.
- If 2-step verification is enforced for the whole administrators group
  (Control Panel > Security > Account, 2-factor authentication
  enforcement), enforce it for the named users instead and leave this
  account out. Menu names vary between DSM releases.
- Deny it every application it does not need (Control Panel > User &
  Group > the user > Applications): the adapter uses the DSM Web API only,
  no File Station, SMB, AFP, FTP, rsync or terminal access.
- Scope it on the network with the DSM firewall (Control Panel > Security
  > Firewall): allow TCP 5001 from the VCF Operations collector (or Cloud
  Proxy) that runs this instance and deny the rest for that port, so a
  leaked credential is usable from nowhere else. DSM Auto Block (Control
  Panel > Security > Protection) stays on; the adapter's login back-off
  keeps a wrong password far below its default threshold.

Every other endpoint is optional. When one is refused or unavailable (a
model without fans, Virtual DSM, a service that is not installed, a
missing privilege), the adapter logs one WARN naming the endpoint, keeps
collecting everything else, and skips only what depends on it, never
publishing a placeholder value. Existing objects that depend on it (iSCSI
LUN or NFS export objects) are reported ERROR each cycle while it is
unread, and the framework logs its own line for each of them:

| Optional endpoint | Skipped while it is not readable |
|---|---|
| `SYNO.Core.Hardware.FanSpeed` | DiskStation `Fan` properties |
| `SYNO.Core.FileServ.NFS` | DiskStation `NFS` service enabled properties |
| `SYNO.Core.ISCSI.LUN` | iSCSI LUN objects (existing ones go ERROR), their Volume and Datastore relationships |
| `SYNO.Core.ISCSI.Target` | iSCSI LUN target IQN, enabled and portal properties |
| `SYNO.Core.Network.Interface` | DiskStation NIC count, the NFS export Datastore cross-link |
| `SYNO.Core.CurrentConnection` | NFS client counts (DiskStation and NFS export) |
| `SYNO.Core.Share` | NFS export objects (existing ones go ERROR), their Volume and Datastore relationships |
| `SYNO.Core.FileServ.NFS.SharePrivilege` (per share) | that share's NFS rules: an existing NFS export on it goes ERROR (no `rule_count` or `allowed_clients` is published), the relationships of the Volume that share sits on are left unchanged (other Volumes still refresh), and a share not yet known as an export is not discovered until its probe succeeds |
| `SYNO.Core.ExternalDevice.UPS` | the UPS object |

The UPS read keeps its build 30 logging: an INFO each cycle. The
per-share NFS rule probe (build 32) runs only while the NFS service is
on: when `SYNO.Core.FileServ.NFS` reports NFS off, no share is probed and
no share is an NFS export. An existing NFS export object is then reported
ERROR each cycle, with a reason that says NFS is off (it publishes
nothing, so no stale value is refreshed), and its Volume edge is dropped
because Volumes are emitted with their known children only; its VMWARE
Datastore edge, if any, stays. The object is not removed: delete it, or
turn NFS back on and it collects again with its Volume edge. While NFS is on, a share's answer is
reused for up to 15 minutes before every share is probed again (a new
share is probed at once); a share whose probe fails is probed again each
cycle and logs its WARN at most once an hour while it keeps failing.

While the iSCSI LUN list or the share list cannot be read, no Volume
relationships are refreshed (a Volume's LUN, NFS export and SSD cache
edges stay as they were), so a temporary failure does not detach them.
When the NFS rule probe of a share fails, only the Volume that share sits
on (its `vol_path`) keeps its edges as they were; every other Volume
refreshes. If the failed share's volume is not known, no Volume is
refreshed.

A NAS without iSCSI (SAN Manager not installed) answers the iSCSI LUN and
target lists with DSM error 102 (the API does not exist). The adapter
reads that as "no LUNs, no targets" once two consecutive collection
cycles get 102 from the same list, logs one INFO per instance
configuration, and keeps refreshing Volume relationships, so SSD caches
and NFS exports still attach to their Volume. The first 102 is treated as
not read (existing LUNs go ERROR for that cycle and Volume relationships
are left unchanged), because DSM can answer 102 for a moment while SAN
Manager is still starting after a NAS reboot. Any other answer resets the
count. On a NAS without iSCSI this costs one cycle after the instance is
created or edited.

The case that stays frozen is a permanent refusal: an account without the
privilege to read the LUN list or the share list (DSM error 105, for
example), or a share whose NFS rule probe is always refused. Then Volume
relationships are never refreshed (for a refused probe, only those of the
Volume the share sits on): a new SSD cache, LUN or NFS export is not
attached to its Volume, and a removed or moved SSD cache keeps its old
Volume edge, until the account can read them. The `Optional DSM endpoint
... is not readable` WARN and the per-share probe WARN name the cause. Use
an administrators-group account (see above).

> **Session note:** the adapter manages its own DSM session. It logs in to
> obtain a `_sid` session token, passes `_sid` on every Web API call, and
> re-authenticates automatically when DSM reports the session expired (DSM
> error 106 / 107 / 119). The `_sid` value is redacted from all logs and
> error messages.

## Network Requirements

| Port | Protocol | Direction | Purpose |
|------|----------|-----------|---------|
| 5001 | HTTPS    | Collector → DiskStation | DSM Web API (system, storage, iSCSI, NFS, UPS reads) |

The optional Datastore cross-link reads VMWARE Datastores from the VCF
Operations Suite API, by one of two paths:

- **Ambient (default, the three Suite API fields blank):** the local
  Suite API on the node that runs the instance, with that node's own
  credentials. No extra network configuration. It works on the primary or
  an analytics node; on a remote collector or Cloud Proxy it usually reads
  nothing, and the cross-link is skipped with one WARN.
- **Explicit (remote collector or Cloud Proxy):** set the Suite API URL
  to the **primary or analytics node FQDN** and a Suite API username and
  password (credential fields below). The collector then needs outbound
  **HTTPS (TCP 443) to that node**:

| Port | Protocol | Direction | Purpose |
|------|----------|-----------|---------|
| 443 | HTTPS | Collector or Cloud Proxy → VCF Operations primary/analytics node | Suite API, explicit Datastore cross-link only |

## TLS — certificate trust

DSM ships a self-signed certificate on port 5001. By default
(`allowInsecure` is `false`) the adapter validates the NAS certificate
chain through the VCF Operations platform trust manager. A chain is
trusted when it is issued by a CA in the VCF Operations trust store, or
when it is a certificate you accepted for this instance.

When you click **Validate Connection** (build 31 and later):

1. **The certificate is already trusted** (issued by a CA the VCF
   Operations trust store holds): validation goes straight on to the
   connection test. No dialog.
2. **The certificate is not trusted** (DSM's factory self-signed
   certificate, or a private CA VCF Operations does not know): VCF
   Operations shows its **Review and Accept Certificate** dialog with the
   NAS certificate. Check the details (issuer, validity, thumbprint
   against what DSM shows under Control Panel > Security > Certificate)
   and accept it. Validation then runs the connection test. The accepted
   certificate is stored on this instance and trusted on every collection
   cycle; validation stays on.

Enter the NAS by a name its certificate carries (a DNS name in its
subject alternative names, a wildcard included); when it is entered by an
IP address the certificate does not list, the review dialog is unproven
and Allow Insecure SSL may be the only path.

If the NAS certificate changes later (renewed or replaced), collection
fails with a certificate error. Open the instance, click **Validate
Connection** and accept the new certificate.

Setting **`allowInsecure=true`** turns certificate validation off, so the
DSM credentials and session id go to whatever answers at that address,
and no certificate is offered for review. A self-signed NAS does not need
it: accept its certificate once as above instead. While it is on, the
adapter logs a WARN each time the instance is configured.

Upgrading from build 30 or earlier: existing instances keep their stored
`allowInsecure` value. An instance created with the old default stores
`true` and keeps skipping validation until you edit it to `false`. Only
the exact value `true` (any letter case) turns validation off. This is
stricter than build 30, which skipped validation for every value except
`false`: an existing instance whose stored value is blank, `yes`, `1` or
any other text now validates the NAS certificate, and if that certificate
is untrusted its collection fails with a certificate message until you
open the instance, click **Validate Connection** and accept the
certificate (or set `allowInsecure=true`).

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
| VCF Ops Suite API URL | `vrops_url` | No | blank | Remote collector or Cloud Proxy only: the primary or analytics node FQDN (a full URL, a bare host or `host:port`). Used only when the username and password below are both set. |
| VCF Ops Suite API Username | `vrops_username` | No | blank | Remote collector only: a VCF Operations account that can read VMWARE Datastores. The adapter's own log lines do not name it, but the shared framework Suite API client still logs the account name at INFO on every configure on this path, until the framework is fixed (factory issue pending). |
| VCF Ops Suite API Password | `vrops_password` | No | blank | Remote collector only (masked). |

Username, password and the three Suite API fields are on the
credential; leave the Suite API fields blank on a primary or analytics
node (the ambient path). See Network Requirements for the explicit
path's HTTPS requirement.

## Step-by-Step Installation

1. Install the `.pak` file via **Administration > Integrations > Repository > Add**
   (9.x; on 8.x, **Data Sources > Integrations > Repository**).
2. After installation, navigate to **Administration > Integrations > Accounts**
   (9.x; on 8.x, **Data Sources > Integrations > Accounts**).
3. Click **Add Account** and select **VCF Content Factory Synology DiskStation**.
4. Fill in the configuration fields above (host, port `5001`, DSM
   credentials; leave `allowInsecure` at `false`).
5. Click **Validate Connection**. For a self-signed or private-CA NAS,
   VCF Operations shows the NAS certificate for review: accept it (see
   TLS above). Then click **Add**.
6. The adapter discovers the storage tree and begins collecting on the
   next cycle (default 5 minutes).

## Troubleshooting

- **Test Connection fails on TLS**: the NAS certificate is untrusted and
  was not accepted (see TLS above). Click **Validate Connection** again
  and accept the certificate in the review dialog. Set
  `allowInsecure=true` only if you accept an unvalidated connection.
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
