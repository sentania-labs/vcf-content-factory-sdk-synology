# VCF Content Factory Synology DiskStation

Monitors Synology DiskStation NAS devices via the DSM Web API (Tier 2 SDK adapter).

## Documentation

Full docset (overview, installing & configuring, inventory tree): [`docs/README.md`](docs/README.md).

## What it monitors

| Object | Metrics | Properties |
|---|---|---|
| **Synology World** | Aggregation root, one per DiskStation (build 34) | — |
| **DiskStation** | CPU, load averages, memory, network, temperature, uptime, NFS ops/latency | Model, hostname, firmware, DSM system health, fan health, fan mode |
| **Storage Pool** | Capacity (total, allocated, allocated %) | RAID type, status, disk count |
| **Volume** | Capacity, IO (read/write throughput, IOPS, utilization), SSD cache hit rates | Filesystem type, status, volume path |
| **Disk** | Temperature, SMART health, IO (read/write throughput, IOPS, utilization), remaining life | Model, firmware, serial, vendor, disk type, drive family, slot ID, capacity |
| **iSCSI LUN** | IO (read/write IOPS, throughput, latency) | Name, size, location, type, target IQN |
| **NFS Export** | Capacity (used, logical, quota usage %), active client count | Export path, volume path, allowed clients, CoW, compression |
| **SSD Cache** | Read/write hit rates, capacity (total, occupied, reusable), metadata memory | Mode, status, mount volume |
| **UPS** | Battery charge %, runtime | Status, mode |

**Cross-adapter stitching**: iSCSI LUNs and NFS exports are linked to VMWARE Datastores
via NAA transform and NFS export path matching. Clicking a Datastore shows the backing
Synology storage health.

## Bundled alerts

The pak installs these alerts (build 35, issue #35), each with its
symptoms and a recommendation. Pak content is enabled in the default
policy, so all nine are on after install; turn one off in the policy
like any other alert. Each condition holds for 3 collection cycles before
the symptom triggers and 3 before it cancels.

| Alert | Object | Condition | Severity | On by default |
|---|---|---|---|---|
| Storage Pool Not Healthy | Storage Pool | `Configuration\|status` is a DSM failure value (`degrade`, `crashed`, `deverr`) | Critical | yes |
| Volume Not Healthy | Volume | `Configuration\|status` is a DSM failure value (`degrade`, `crashed`, `deverr`, `ro`) | Critical | yes |
| Volume Nearly Full | Volume | `Capacity\|usage_pct` at or above 85 % / 95 % | Warning / Critical | yes |
| Disk Failing | Disk | `Health\|smart_status` is `failing`, `degraded` or `access_err` / `Health\|unc_sectors` above its dynamic threshold | Critical / Warning | yes |
| SSD Cache Failure | SSD Cache | `Hardware\|disk_failure_count` not 0, or `Configuration\|status` a DSM failure value (`degrade`, `crashed`, `deverr`, `ro`) | Critical | yes |
| Fan Failure | DiskStation | `Fan\|fan_health` is `failed` (DSM System Health reports a fan failure) | Critical | yes |
| UPS On Battery | UPS | `Status\|status` is `usb_ups_status_battery` / `usb_ups_status_low_batt` | Warning / Critical | yes |
| Storage Degraded | DiskStation | DSM Storage Manager's storage verdict is Critical / Warning (`Health\|storage_status` `danger` / `attention`), a pool is missing (`Health\|missing_pools` not 0) / awaiting assembly (`Health\|detected_pools` not 0), or the system partition crashed (`Health\|system_partition_crashed` `true`) / needs repair (`Health\|system_partition_needs_repair` `true`) | Critical / Warning, per symptom | yes |
| Expansion Unit Lost | DiskStation | DSM reports space affected by a lost expansion unit (`Health\|expansion_unit_lost` `true`) **and** the storage verdict is Critical (`Health\|storage_status` `danger`), or an incompatible expansion unit (`Health\|incompatible_expansion_units` not 0) | Critical / Warning | yes |

A status the adapter could not read is not published, so no alert fires
on a missing value; a disk whose SMART DSM cannot read (`unknown`) raises
nothing. Limits: DSM's System Health reports only its highest-ranked
problem, so a fan failure is not visible (fan health `unknown`) while
another problem outranks it, and a Fan Failure alert that is already
open clears after 3 cycles once another problem outranks the fan, even
though the fan is still failed; the pool and volume patterns leave out
DSM's `pool_crashed` and `pool_error`, because DSM keeps those in a
different field (`space_status.status`) that the adapter does not read,
so they never appear in `Configuration|status`; the UPS values come from DSM's own UI code
and have not yet been seen on a live UPS; the uncorrectable-sector
symptom needs a few days of history before its dynamic threshold exists;
Storage Degraded and Expansion Unit Lost read DSM Storage Manager's own
health card (`SYNO.Storage.CGI.Storage load_info`, matched the way DSM's
`storage_panel.js` reads it) and have not been seen degraded live (the
lab NAS has no failed pool or expansion unit). Expansion Unit Lost needs
Storage Critical too, because DSM's UI shows nothing on the lost-unit flag
alone; a degraded storage verdict also raises Storage Degraded, so the two
can be open together.
The status conditions are regular expressions over DSM's own failure
values; exact patterns and the symptom names are in
[REFERENCE.md, Bundled alerts](REFERENCE.md#bundled-alerts).

## Prerequisites

- Synology DSM 7.2 or later
- A dedicated DSM account in the administrators group, without 2-step verification, with API access on port 5001 (HTTPS); see [docs/installing.md](docs/installing.md) for hardening it
- VCF Operations 8.x or 9.x

## Installing: allow unsigned paks first

This pak is unsigned, like every pak the VCF Content Factory builds (Broadcom
offers no way for a third party to sign one). VCF Operations 9.0 and 9.1 refuse
unsigned paks out of the box; behavior on 8.x is unverified. The setting below
must be on for each install or upgrade of an unsigned pak:

1. Log in to `https://<primary node>/admin` as `admin`.
2. Administrator Settings, Security Settings: click **Activate Unsigned PAK
   Installation** on 9.1 (**Enable Unsigned PAK Installation** on 9.0). The
   change is refused while any cluster node is down.
3. When uploading the pak (**Repository** tab > **Add**, see Install
   below for where the tab is on your version), tick **Ignore the
   PAK file signature checking**. The box only appears once the setting is
   on.

With the setting off, the install fails with
`unsigned_pak_installation.not.allowed`. This is a cluster-wide security
setting: it lets any unsigned pak be installed, not only this one, so decide
it deliberately and consider turning it off again after installing. If you do,
it has to go back on before you install the next build. Upgraded appliances
keep whatever state the setting had before the upgrade.

## Install

1. Open VCF Operations and go to the **Repository** tab of Integrations: on 9.1.1, **Operate** tab > **Administration** > **Integrations** > **Repository**; on 9.0.2 and 8.18.7, **Administration** > **Integrations** > **Repository** (verified 2026-10-28)
2. Click **Add**
3. Select the `.pak` file from this package
4. Accept the EULA and wait for installation to complete (~30 seconds)

## Configure

After installation, create an adapter instance:

1. Go to the **Accounts** tab of Integrations (the default tab): on 9.1.1, **Operate** tab > **Administration** > **Integrations** > **Accounts**; on 9.0.2 and 8.18.7, **Administration** > **Integrations** > **Accounts** (verified 2026-10-28)
2. Click **Add Account** and select **VCF Content Factory Synology DiskStation**
3. Fill in:
   - **Host / IP Address**: Your NAS hostname or IP (e.g., `storage.example.com`)
   - **Port**: HTTPS port (default `5001`)
   - **Allow Insecure SSL**: default `false` (the NAS certificate is validated). `true` turns validation off (lab use only); any other value validates. A self-signed NAS does not need it: Validate Connection offers its certificate for review, you accept it once and validation stays on. Enter the NAS by a name its certificate carries; when it is entered by an IP address the certificate does not list, the review dialog is unproven and Allow Insecure SSL may be the only path. Existing instances keep their stored value on upgrade; see [docs/installing.md](docs/installing.md).
   - **Username**: the dedicated DSM account (administrators group, no 2-step verification)
   - **Password**: Account password
4. Click **Test Connection**, then **Save**

Discovery runs immediately. Metrics begin collecting on the next 5-minute cycle.

## Upgrading

### To build 35: LUN and NFS latency drop by a factor of 1000

DSM reports iSCSI LUN `read_avg_latency` / `write_avg_latency` and NFS
`total_max_latency` in microseconds (its own Resource Monitor shows 2188 as
"2.2 ms"), and earlier builds published them as sent under the unit ms
(LUN latency since build 32, NFS max latency in every release). From
build 35 the adapter divides by 1000 (issue #42), so iSCSI LUN `IO|read_latency`
and `IO|write_latency` and DiskStation `NFS|nfs_max_latency` are real
milliseconds. Charts spanning the upgrade show a step down, and any
symptom, view or super metric threshold on these keys needs re-checking.
Key names and units are unchanged. NFS max latency is now skipped, not
published as 0, when DSM does not send it.

Dynamic thresholds and anomaly history on `IO|read_latency`,
`IO|write_latency` and `NFS|nfs_max_latency` were learned on values 1000
times too large. VCF Operations relearns them from the new data, so expect
spurious anomalies on these keys until it has a few days of history at the
new scale.

Build 35 also adds the bundled alerts (section Bundled alerts). The Disk
Failing alert's uncorrectable-sector symptom is a dynamic-threshold
symptom: on a fresh install, or on an upgrade to this build, it has no
band yet and cannot fire until VCF Operations has learned one (a few
days); the SMART half of the alert works from the first cycle.

`Fan|fan_status` keeps its key and value (DSM's `cool_fan`) but is now
labelled "Fan Mode Support": it says which fan speed modes the model
offers, not whether a fan works. Fan health is the new `Fan|fan_health`,
from DSM's System Health (`SYNO.Core.System.SystemHealth`), shown with the
new `Health|system_health`, `system_health_rule` and
`system_health_message` properties. A view or symptom that read
`fan_status` as health needs re-pointing to `fan_health`. Pool, volume,
SSD cache, SMART and UPS statuses that DSM does not send are now skipped
instead of published as an empty value.

### To build 35: objects DSM no longer reports are deleted, and the first upgrade deletes the pre-build-34 leftovers

**Build 35 marks objects DSM no longer reports as Not Existing, and VCF
Operations then deletes them; on the first upgrade that includes every
leftover from before build 34, at once.** An object missing from 12
consecutive complete collection cycles (an hour at the default 5-minute
interval) is marked Not Existing, and VCF Operations deletes it, with its
metric history, alerts and group membership, after the Deleted Objects
retention (Global Settings; 168 hours by default, 6 hours on some labs).
Counting starts on the first build 35 cycle and covers every object the pack
registered in the past under a key it no longer produces. This cannot be
undone.

That includes the pool, volume, disk, SSD cache, iSCSI LUN, NFS export and
UPS objects from before build 34 (no **DiskStation ID** identifier; section
"To build 34" below), which until now stayed Data Receiving with frozen
values until deleted by hand. On devel_9_1 and devel_8_18 (read 2026-10-28)
each has 29 such objects: 16 NFS exports, 7 disks, 2 iSCSI LUNs, 1 storage
pool, 1 volume, 1 SSD cache and the old `Synology World`. All but the World
are marked about 12 cycles after the upgrade. The World kind is never marked
(it is the pack's own root object), so delete the old `Synology World` by
hand once its children are gone.

Only a kind read in full counts a miss, so a failed read never deletes
anything: one share whose NFS rule probe failed counts no export miss that
cycle, and a failed UPS, SSD cache, iSCSI or System Health read counts no
miss for its kind. A storage pool DSM reports missing or awaiting assembly
(an expansion unit unplugged, drives pulled, a pool to assemble after a
migration) is not gone either: while DSM lists one in `missing_pools` or
`detected_pools`, or sends neither key, no pool, volume, disk, SSD cache,
LUN or NFS export is counted. Lists that contradict each other count
nothing for the kind they point at (a volume on a pool or cache the lists
omit, a shared cache DSM reports missing), and neither does a share list
shorter than its `total`. An empty list is not proof that everything is
gone: it counts no miss unless another DSM field shows the kind really is
empty, so the last LUN, the last NFS export and the last volume on a pool
are never removed automatically; delete those by hand. A missing pool, or a
lost or incompatible expansion unit, deletes nothing. The missing pool's own
object keeps the last status DSM gave while it was listed; the DiskStation
shows the problem instead, through `Health|missing_pools`,
`Health|storage_status` (DSM Storage Manager's own Healthy, Warning or
Critical) and `Health|expansion_unit_lost`, which raise the Storage
Degraded and Expansion Unit Lost alerts.
Details: REFERENCE.md, Removed objects. The framework's
own warning is in the factory's buildkit release notes
(`.github/release-notes-buildkit.md`, Behavior since 1.0.22, "On first
opt-in, every orphan goes").

Before upgrading, list the pack's objects (inventory, adapter type **VCF
Content Factory Synology DiskStation**) and export anything whose history
you want to keep.

### To build 34 (from 1.0.0.30, 0.0.0.33 or earlier): Synology objects are re-created once

Build 34 fixes two or more NASes writing into the same objects (issue #11):
objects are now identified by their DiskStation's DSM serial number. Pool,
volume, disk, SSD cache, iSCSI LUN, NFS export and UPS objects, and the
Synology World, therefore get a new identity, and VCF Operations creates
them as new objects on the first collection after the upgrade.

- **History does not carry over.** Metrics and properties collected before
  the upgrade stay on the old objects; the new objects start empty. The
  DiskStation object itself keeps its identity and history when DSM reports
  a serial (it was already keyed by serial). When DSM sends no serial, it
  is re-created too: it was keyed by the literal `unknown`, and is now keyed
  by `host:<host>:<port>`.
- **The old objects get no new values, but still look collected.** The
  adapter publishes no metrics or properties for an object without the new
  identity, and logs it once at INFO (`does not carry this DiskStation's
  identity`). VCF Operations still shows them as **Data Receiving**, with
  their last values frozen as if current, and any alert on them stays in
  its last state, until you delete them. Do not read a green old object as
  healthy. Their alerts, and any custom group, view, dashboard or report
  pinned to a specific old object, need re-pointing at the new object.
  Content that works by object type is unaffected.
- **The DiskStation shows under two Worlds.** When the DiskStation keeps
  its identity (DSM reports a serial), it is a child of both the old
  `Synology World` and the new `Synology World (<serial>)`: the old World
  keeps its last relationships. This goes away when the old World is
  deleted.
- **Datastore links.** The VMware Datastore to iSCSI LUN / NFS export links
  are made for the new objects on the first cycle; the old objects keep
  theirs until they are deleted.

**Clean up the old objects** once the new tree has collected (one or two
cycles): in the VCF Operations inventory, list the objects of the adapter
type **VCF Content Factory Synology DiskStation**. Do not go by collection
state (the old objects still show Data Receiving) or by health; go by name
and identifiers. (From build 35 the adapter marks these itself, World
excepted, about 12 cycles after the upgrade; see the section above.) The
old objects are the World named exactly
`Synology World` (the new one is `Synology World (<serial>)`), and every
pool, volume, disk, SSD cache, LUN, NFS export and UPS whose identifiers have
no **DiskStation ID**. Select them and delete them. If you run several NASes
that collided before the upgrade, delete the old objects of all of them:
their data and relationships were a mix of the NASes and cannot be split.
Deleted old objects do not come back: the adapter discovers only the new
identities. (The inventory menu path differs between VCF Operations versions
and was not re-verified for this note.)

## Object hierarchy

```
Synology World (20B0RYRXRF3KF)
└── DS1520+ 20B0RYRXRF3KF
    ├── Storage Pool 1
    │   ├── Volume 1
    │   │   ├── NFS Export: share-name
    │   │   ├── iSCSI LUN: lun-name
    │   │   └── SSD Cache: alloc_cache_1_1
    │   ├── Drive 1
    │   ├── Drive 2
    │   ├── ...
    │   ├── Cache device 1
    │   └── Cache device 2
    └── UPS (if connected)
```

Use the **Synology DiskStation Storage Tree** traversal spec to navigate the hierarchy.

Every object is identified by its DiskStation's DSM serial number (build 34),
so each NAS gets its own tree; add one account per NAS. When DSM sends no
serial (a non-administrator account or a Virtual DSM may not get one), the
account's host and port stand in for it, and the adapter log says so once.
See [REFERENCE.md](REFERENCE.md#diskstation-identity).

## Building from source

You don't need this repo's CI or the VCF Content Factory checkout to
build the `.pak` — the toolchain is a portable tarball. You need:

- **JDK 11+** (`javac` + `jar` on PATH)
- **python3** with `pyyaml` (`python3 -m pip install pyyaml`)
- **The GitHub CLI** (`gh`) — used to download the build toolchain
  below. The factory repo is public, so no `gh auth login` is needed
  for the download (authenticate only if you hit anonymous API rate
  limits). No `gh`? See the `curl` alternative under step 1.
- **The Broadcom adapter SDK jar** (`vrops-adapters-sdk-2.2.jar`).
  This is a Broadcom build artifact with no public redistribution
  channel — it is **never** bundled in the toolchain or this repo.
  Get it from your own VCF Operations appliance:

  ```
  scp root@<appliance>:/usr/lib/vmware-vcops/common-lib/vrops-adapters-sdk-2.2.jar .
  ```

  (Also present at
  `/usr/lib/vmware-vcops/suite-api/WEB-INF/lib/vrops-adapters-sdk.jar`.
  Partners can pull it from the Broadcom TAP / partner SDK portal
  instead.)

Then, from the root of this repo:

```bash
# 1. Fetch the build toolchain (pin a full sdk-buildkit-vX.Y.Z tag for
#    reproducibility, or use the floating major sdk-buildkit-v1)
gh release download sdk-buildkit-v1 \
  --repo sentania-labs/vcf-content-factory \
  --pattern 'sdk-buildkit-*.tgz'
# No gh? The asset is public — fetch it with curl instead:
#   curl -sL https://github.com/sentania-labs/vcf-content-factory/releases/download/sdk-buildkit-v1/sdk-buildkit-v1.tgz -o sdk-buildkit-v1.tgz
tar xzf sdk-buildkit-*.tgz

# 2. Point the kit at your SDK jar and build
export VCFCF_SDK_JAR=/path/to/vrops-adapters-sdk-2.2.jar
python3 -m sdk_buildkit validate-sdk .   # cheap loop: compile-check
python3 -m sdk_buildkit build-sdk .      # emits the .pak
```

The kit carries everything else it needs (including the
`vcfcf-adapter-base.jar` framework runtime that ends up in the pak's
`lib/`). `validate-sdk` is the fast iteration loop; exhaust it before
building paks.

**Dev builds vs releases.** Anything you build this way is a *dev
build*. The **official** artifact for this repo is the one its own CI
builds and attaches to a GitHub Release when a `v*` tag is pushed —
deterministic, no developer machine in the path.

**If you fork this repo**, the CI workflow
(`.github/workflows/build-pak-on-tag.yml`) needs one adjustment
before your own `v*` tags will build (it already runs on GitHub-hosted
`ubuntu-latest`, so no runner change is needed).

**SDK jar sourcing**: the upstream workflow fetches the Broadcom
   jar from a private repo via an `SDK_RUNTIME_SSH_KEY` deploy-key
   secret you won't have. Replace that step with your own source —
   e.g. store the appliance-extracted jar in your own private repo or
   an Actions secret/artifact store. Then **also update the
   `--sdk-jar` argument** on the `build-sdk` line of the workflow to
   point at wherever your replacement step puts the jar. The explicit
   `--sdk-jar` flag overrides `VCFCF_SDK_JAR`, so setting the env var
   alone is not enough — if you leave `--sdk-jar _sdk_runtime/...` in
   place the build will look for the upstream path and fail. Do **not**
   commit the jar to a public repo (no redistribution).

## License

MIT — see LICENSE in the VCF Content Factory repository.
