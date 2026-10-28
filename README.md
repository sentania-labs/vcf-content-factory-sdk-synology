# VCF Content Factory Synology DiskStation

Monitors Synology DiskStation NAS devices via the DSM Web API (Tier 2 SDK adapter).

## Documentation

Full docset (overview, installing & configuring, inventory tree): [`docs/README.md`](docs/README.md).

## What it monitors

| Object | Metrics | Properties |
|---|---|---|
| **Synology World** | Aggregation root, one per DiskStation (build 34) | — |
| **DiskStation** | CPU, load averages, memory, network, temperature, uptime, NFS ops/latency | Model, hostname, firmware, fan status |
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
3. When uploading the pak (**Data Sources > Integrations > Repository >
   Add** on 9.1; **Administration > Solutions** on 8.x), tick **Ignore the
   PAK file signature checking**. The box only appears once the setting is
   on.

With the setting off, the install fails with
`unsigned_pak_installation.not.allowed`. This is a cluster-wide security
setting: it lets any unsigned pak be installed, not only this one, so decide
it deliberately and consider turning it off again after installing. If you do,
it has to go back on before you install the next build. Upgraded appliances
keep whatever state the setting had before the upgrade.

## Install

1. Open VCF Operations and go to **Data Sources > Integrations > Repository** (9.1, verified 2026-10-16; on 8.x, **Administration > Solutions**)
2. Click **Add**
3. Select the `.pak` file from this package
4. Accept the EULA and wait for installation to complete (~30 seconds)

## Configure

After installation, create an adapter instance:

1. Go to **Data Sources > Integrations > Accounts** (9.1, verified 2026-10-16; on 8.x, **Administration > Solutions**)
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
and identifiers. The old objects are the World named exactly
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
