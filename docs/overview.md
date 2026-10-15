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
- **A failed login is not retried per resource (build 31).** When the
  per-cycle snapshot cannot be built (wrong or rotated password, disabled
  account, NAS down), the failure is cached: the first caller of the
  cycle (usually the discovery step) reports it, and every other resource
  of that cycle fails from the cached result without contacting the NAS.
  The adapter log gets one `Synology snapshot build failed` WARN per
  attempted login, plus the framework's own line for the discovery step
  and for each resource that fails from the cached result. Each framework
  line is one line naming the original failure (its class and message),
  with no stack trace, on the cycle that tried the login as on the cycles
  served from the cache; the original with its stack is logged at DEBUG.
  After a DSM login refusal (error 400, 401, 402, 403, 404, 406, 407, 408, 409 or
  410, which DSM returns in the body of an HTTP 200) no login is tried for
  15 minutes, then 30, then every 60 minutes while the refusals continue,
  unless the instance is edited. Every failed login counts toward DSM Auto
  Block, which blocks the collector's IP (possibly shared with other
  integrations), so a wrong password now costs a handful of failed logins
  over hours instead of one per resource per cycle. Any other failure is
  tried again on the next cycle. During a wrong-password back-off the
  adapter instance still shows **Collecting** in the account list while
  every Synology object is ERROR: the adapter cannot change the instance
  status (a framework limitation).
- **An unreachable NAS costs one attempt per cycle and is DOWN (build
  31).** The DSM snapshot is built at most once per collection cycle,
  however many objects the NAS has and however long the build takes. When
  the NAS cannot be reached (connection refused, connect or request
  timeout, unknown host name, no route), the first object pays the
  timeouts once and every other object fails at once from the cached
  result, so the cycle stays inside the 5-minute interval (before build
  31 each of about 25 objects waited out its own timeouts, close to 40
  minutes per cycle). The objects are marked DOWN; a refused login, a
  certificate failure or an unreadable answer is ERROR. A NAS that
  answers the required reads and then stops answering ends the build at
  the first optional read or NFS share probe that times out, instead of
  each remaining call waiting out its own timeouts. Stop Collecting
  ends a snapshot build between DSM calls; if the stop lands while one
  resource's own collect is building the snapshot, that resource shows
  ERROR until its next cycle, because the framework sets a status on
  every resource whose collect throws and offers no "stopped" outcome.
  The HTTP connect and read
  timeouts are one 30 s value, because the framework HTTP client offers
  no separate connect timeout.

- **One optional DSM endpoint no longer hides the whole NAS (build 31).**
  Only DSM info, system info, utilization and storage load_info are
  required. Any other endpoint that fails (FanSpeed on Virtual DSM, NFS or
  iSCSI not installed, CurrentConnection without the privilege) is logged
  once at WARN by the adapter and only what depends on it is skipped
  (existing LUN or NFS export objects that depend on it are ERROR each
  cycle, with the framework's own log line for each); the DiskStation,
  pools, volumes, disks and SSD caches are still discovered and
  collected. A NAS without iSCSI answers DSM error 102 (the API does not
  exist), which is read as no LUNs and no targets from the second
  consecutive cycle that answers it (a single 102, as while SAN Manager
  starts after a reboot, is not read), so Volume relationships still
  refresh there. A permanent refusal of the LUN or share list (a
  missing privilege) leaves Volume relationships unrefreshed until the
  account can read it. See docs/installing.md for the endpoint table and
  the minimum DSM privileges.

- **Editing the instance releases the old DSM session (build 31).** A
  reconfigure (password change, TLS toggle, host change) logs the previous
  DSM session out and discards the previous Suite API stitcher (the
  framework discards the previous HTTP client), best effort, using the
  same teardown as removing the instance.
  Before build 31 every edit left a DSM session open until DSM expired it.
  The old DSM client is closed first: a collection still running on it
  never logs in again (no leftover session, and no login with an old
  password counting toward DSM Auto Block), and its logout is one attempt
  with a 5 s timeout, so an edit made because the old address is dead is
  not held up.

- **One oddly shaped object no longer wipes the topology (build 31).** A
  storage pool without `pool_path`, an iSCSI LUN without `location` (a
  file-based or thin LUN on some DSM versions) or a volume without an
  identifier is left out of the one edge that needs that field, with one
  INFO line per cycle naming it. Every other relationship, including the
  Datastore cross-link, is still published. Before build 31 such an object
  threw, and no relationships at all were published, every cycle.

## Known Limitations

- **No remediation / control actions.** The pack is read-only monitoring.
- **Datastore cross-link requires the Suite API.** On a remote collector
  with no ambient Suite API credentials, the informational Datastore
  cross-link is omitted (storage collection is unaffected).
- UPS metrics appear only when a UPS is connected to the DiskStation.
