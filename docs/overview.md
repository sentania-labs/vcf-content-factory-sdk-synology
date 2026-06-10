# Overview — VCF Content Factory Synology DiskStation

## What's in the Pack

VCF Content Factory Synology DiskStation is a Tier 2 (Java SDK) management
pack that monitors Synology DiskStation NAS devices through the DSM Web
API and presents their storage hierarchy as a full object tree in VCF
Operations.

Each collection cycle the adapter performs a single DSM API pull into a
per-cycle snapshot and emits capacity, performance, and health metrics
across the storage stack — from the DiskStation system down through pools,
volumes, disks, and the iSCSI LUNs / NFS exports that front them for
clients.

### Resource kinds

The pack discovers a nine-kind storage tree (see `inventory-tree.md` for
the traversal spec and identifying keys, and `REFERENCE.md` for the full
metric list):

| Kind | Key | What it represents |
|------|-----|--------------------|
| Synology World | `SynologyWorld` | Aggregation root (singleton per instance). |
| Synology Diskstation | `SynologyDiskstation` | The NAS appliance — system, CPU, memory, network, temperature, NFS service. |
| Synology Storage Pool | `SynologyStoragePool` | RAID pool capacity and configuration. |
| Synology Volume | `SynologyVolume` | Volume capacity, IO, and SSD-cache hit rates. |
| Synology Disk | `SynologyDisk` | Per-drive health (SMART, temperature, remaining life) and IO. |
| Synology iSCSI LUN | `SynologyIscsiLun` | LUN IO (IOPS, throughput, latency) and configuration. |
| Synology NFS Export | `SynologyNfsExport` | Export capacity, quota, and active client count. |
| Synology SSD Cache | `SynologySsdCache` | Cache hit rates and capacity. |
| Synology UPS | `SynologyUps` | Battery charge and runtime (when a UPS is connected). |

### Metrics scope

Roughly 130 metric and property keys across the nine kinds (see
`REFERENCE.md` for the authoritative list). Highlights:

- **DiskStation** — system temperature, uptime, per-window CPU load,
  memory usage, network RX/TX, NFS ops/latency/client-count.
- **Pools / Volumes** — total/used/free capacity and usage %, plus volume
  read/write throughput, IOPS, and utilization.
- **Disks** — temperature, SMART status, uncorrectable sectors, remaining
  life, and per-disk IO.
- **iSCSI LUNs** — read/write IOPS, throughput, and latency.
- **NFS exports** — used/logical size, quota usage %, active client count.

## Cross-Adapter Behavior

The pack ships an **informational cross-link to VMware storage**. Each
iSCSI LUN and NFS export that backs a real **VMWARE Datastore** is attached
as a child of that Datastore (Datastore → LUN / export), so the
Datastore's existing HostSystem and VM edges light up the storage
dependency graph for free — clicking a VMware datastore surfaces the
backing Synology storage health.

Resolution is by **path identity, never a MOID**:

- **iSCSI LUN** → the LUN UUID is transformed into the Type-6 NAA
  identifier VMware would present (`VMFS:|naa.…|`), matched against the
  Datastore `DataStrorePath`.
- **NFS export** → `<nas_ip>/<volume_path>/<share>`, one key per connected
  NAS IP (covering round-robin / multi-homed mounts), matched against the
  Datastore `DataStrorePath`.

An edge is emitted **only when the computed path matches a Datastore that
actually exists in inventory** — the adapter never mints phantom Datastore
keys. Zero matches on a healthy Suite API connection is legitimate (no
VMware datastore is backed by this NAS) and logged at INFO.

The cross-link is **optional and ambient**: it resolves foreign VMWARE
Datastores over the local VCF Operations Suite API using the collector's
ambient credentials. On a collector where the Suite API is unavailable, the
cross-link is skipped with a single WARN and all storage resources collect
normally — collection is never failed over the optional cross-link.

> **Note:** relationship-edge persistence depends on the bundled framework
> jar. Build 17 picks up the framework `ResourceKey` arg-order fix, so this
> adapter's internal storage-tree edges and the Datastore cross-link persist
> correctly (prior v2 builds emitted edges that were silently dropped at
> persist time).

## Notable Behaviors

- **Unreadable is not a sentinel.** A "success-shaped but empty" DSM payload
  (e.g. `DSM.Info` with no model, or `Core.System.Utilization` with no cpu)
  throws and marks the resource ERROR rather than publishing `0.0`
  sentinels on a GREEN instance. A snapshot refresh failure propagates —
  the adapter never registers a silent zero-resource cycle.
- **Secrets are redacted from logs and errors.** The DSM `entry.cgi` URL
  carries the `_sid` session token on every call and `account=`/`passwd=`
  on login; these are masked in all thrown/logged messages while the
  failing endpoint stays identifiable.
- **Fresh-instance discovery works on VCF Ops 9.0.2.** The adapter
  enumerates its storage tree on the collect path
  (`discoverOnCollect()`), so a freshly created instance populates on its
  first collection cycle. Enumeration serves from the same per-cycle
  snapshot it collects from (no redundant, rate-limit-hostile API pulls).

## Known Limitations

- **No remediation / control actions.** The pack is read-only monitoring.
- **Datastore cross-link requires the Suite API.** On a remote collector
  with no ambient Suite API credentials, the informational Datastore
  cross-link is omitted (storage collection is unaffected).
- UPS metrics appear only when a UPS is connected to the DiskStation.
