# VCF Content Factory Synology DiskStation — Reference

Generated from `describe.xml` and `resources.properties` for build 1.0.0.16.

## Adapter

| Field | Value |
|---|---|
| Adapter Kind | `synology_diskstation` |
| Tier | 2 (Java SDK) |
| Monitoring Interval | 5 minutes |
| License Required | No |

### Credentials

| Field | Key | Type |
|---|---|---|
| Username | `username` | string |
| Password | `password` | string (masked) |

### Connection Settings

| Field | Key | Default | Required |
|---|---|---|---|
| Host / IP Address | `host` | — | Yes |
| Port (HTTPS, default 5001) | `port` | 5001 | No |
| Allow Insecure SSL (true/false) | `allowInsecure` | false | No |

---

## DiskStation identity

Build 34 (issue #11). Every object is identified by its DiskStation, so two
adapter instances (two NASes) never write into the same object.

- The identity is the DSM serial number (`SYNO.DSM.Info` `serial`). It is
  the DiskStation's `serial` identifier, and the identifying
  `diskstation_id` on the World and on every object below the DiskStation,
  next to the object's own DSM-local id (`reuse_1`, `/volume1`, `sata1`,
  ...), which is the same on most DiskStations.
- When DSM sends no serial (blank or missing: a non-administrator account
  or a Virtual DSM may not get one), the identity is the instance's
  configured endpoint, `host:<host>:<port>` (host trimmed and lower-cased),
  announced by one WARN per configuration. It changes only when the host or
  port is edited, which re-creates the objects.
- A cycle whose `SYNO.DSM.Info` has no serial fails (every object ERROR)
  instead of moving the objects to the fallback identity when either a
  serial was read earlier in this configuration, or this adapter instance
  already has a DiskStation object whose `serial` identifier is a real
  serial (not `host:...`, and not build 33's `unknown`). The second check
  reads the instance's existing objects from VCF Operations, so the guard
  holds across a collector restart, a pak upgrade and an account edit. To
  accept the fallback identity anyway, delete that DiskStation object, then
  save the adapter instance (or restart the collector). A serial that
  appears while the fallback is in use is taken, and the objects move to it
  once (one WARN).
- The World is one per DiskStation, named `Synology World (<serial>)`.
- An object that does not carry the current identity (one from before
  build 34, or from an earlier fallback identity) gets no new metrics or
  properties and is logged once at INFO. Up to build 34 VCF Operations
  showed it as Data Receiving with its last values frozen until it was
  deleted by hand. From build 35 it is marked Not Existing after 12 complete
  cycles and deleted by retention, an old World included (see Removed
  objects and the README section Upgrading).

## Object Types

### Synology World

**Identifiers**: `world_id` (World ID), `diskstation_id` (DiskStation ID; build 34, see [DiskStation identity](#diskstation-identity))

---

### Synology Diskstation

**Identifier**: `serial` (Serial Number). Holds the DiskStation identity: the DSM serial, or `host:<host>:<port>` when DSM sends none (build 34, see [DiskStation identity](#diskstation-identity)).

#### System

| Key | Label | Type | Unit | Monitored |
|---|---|---|---|---|
| `system_temp` | System Temperature | metric | C | yes |
| `uptime` | Uptime | metric | sec | yes |
| `model` | Model | property | — | — |
| `hostname` | Hostname | property | — | — |
| `firmware_version` | Firmware Version | property | — | — |
| `firmware_date` | Firmware Date | property | — | — |

#### CPU

| Key | Label | Type | Unit | Monitored |
|---|---|---|---|---|
| `cpu_load_1m` | Load Average (1 min) | metric | none | yes |
| `cpu_load_5m` | Load Average (5 min) | metric | none | yes |
| `cpu_load_15m` | Load Average (15 min) | metric | none | yes |
| `cpu_user_pct` | CPU User % | metric | % | yes |
| `cpu_system_pct` | CPU System % | metric | % | yes |
| `cpu_total_load` | CPU Total Load | metric | % | yes |

The three load averages are the Unix run-queue length (unitless, a 4-core
NAS at full use reads about 4). DSM reports them multiplied by 100; since
build 0.0.0.33 (issue #41) the adapter divides by 100 before publishing, so
DSM's 473 is published as 4.73. A load average DSM does not send is
skipped, not published as 0. Every build before 0.0.0.33, including
v1.0.0.30 and earlier releases, published DSM's value as sent, so history
before the upgrade is 100 times the real load.

#### Memory

| Key | Label | Type | Unit | Monitored |
|---|---|---|---|---|
| `memory_available` | Memory Available | metric | bytes | yes |
| `memory_total` | Memory Total | metric | bytes | yes |
| `memory_usage_pct` | Memory Usage % | metric | % | yes |
| `memory_cached` | Memory Cached | metric | bytes | no |
| `swap_usage` | Swap Usage | metric | bytes | no |
| `swap_total` | Swap Total | metric | bytes | no |

#### Network

| Key | Label | Type | Unit | Monitored |
|---|---|---|---|---|
| `net_rx_bytes` | Network RX | metric | bytes/s | yes |
| `net_tx_bytes` | Network TX | metric | bytes/s | yes |
| `nic_count` | NIC Count | metric | — | no |

#### Fan

| Key | Label | Type | Unit | Monitored |
|---|---|---|---|---|
| `fan_status` | Fan Mode Support | property | — | — |
| `fan_speed_mode` | Fan Speed Mode | property | — | — |
| `fan_health` | Fan Health | property | — | — |

`fan_status` is DSM's `cool_fan`: which fan speed modes the model offers
(`no`, `single`, `yes`), not fan health (display name "Fan Status" before
build 35). `fan_health` (build 35) is `failed` when DSM's System Health
reports a fan failure (`widget:fan_fail`, `cpu_fan_fail`,
`eunit_fan_fail`), `normal` when DSM reports the system healthy, and
`unknown` when another problem outranks the fans (DSM's System Health
returns only its top problem); it is skipped when
`SYNO.Core.System.SystemHealth` was not read. A Fan Failure alert that is
already open cancels after 3 cycles of `unknown`, so it clears when
another problem outranks the fan even though the fan is still failed.

#### Health

| Key | Label | Type | Unit | Monitored |
|---|---|---|---|---|
| `system_health` | System Health | property | — | — |
| `system_health_rule` | System Health Rule | property | — | — |
| `system_health_message` | System Health Message | property | — | — |
| `storage_status` | Storage Status | property | — | — |
| `storage_status_level` | Storage Status Level (DSM) | property | — | — |
| `expansion_unit_lost` | Expansion Unit Lost | property | — | — |
| `incompatible_expansion_units` | Incompatible Expansion Units | property | — | — |
| `missing_pools` | Missing Storage Pools | property | — | — |
| `detected_pools` | Detected Storage Pools | property | — | — |
| `system_partition_crashed` | System Partition Crashed | property | — | — |
| `system_partition_needs_repair` | System Partition Needs Repair | property | — | — |

Build 35: DSM's System Health widget verdict (`SYNO.Core.System.SystemHealth`).
`system_health` is `normal`, `attention` or `danger` (DSM rule type 2, 1,
0); `system_health_rule` is DSM's rule id (`system_health`,
`ups_status_abnormal`, `disk_health_status_error`, ...);
`system_health_message` is the DSM string-table key of the message
(`widget:system_ok` when healthy). Each is skipped when DSM did not send it.

Build 35 (review R4-W2): DSM Storage Manager's own storage verdict, the one
its Overview page's health card shows (Healthy, Warning, Critical), from
storage `load_info` (`storage_panel.js` `loadOverview` and `health-card`,
DSM 7.3.2). Each is skipped when DSM did not send it.
- `storage_status`: `normal`, `attention` or `danger`, the name whose
  `overview_data.sample_level_str` value equals `status_level` (DSM 7.3.2
  sends `status_level_normal`, `status_level_abnormal`,
  `status_level_danger`; without `sample_level_str` the UI's defaults
  `normal`, `abnormal`, `danger` apply). Skipped when the value matches
  none. Danger is "Issues have occurred to Storage Pool ..." in DSM.
- `storage_status_level`: DSM's raw `overview_data.status_level`.
- `expansion_unit_lost`: `overview_data.space_affect_by_eunit_lost`, `true`
  or `false`. DSM's card uses it to explain a danger level ("possibly due
  to connection issues between ... and the expansion units").
- `incompatible_expansion_units`: how many entries `overview_data.incompatible_eunit`
  holds (DSM: Warning, "An incompatible expansion unit ... has been
  detected").
- `missing_pools`, `detected_pools`: how many pools DSM lists in
  `missing_pools` (drives absent: an expansion unit unplugged, drives
  pulled) and `detected_pools` (awaiting Online Assemble). Such a pool has
  left `storagePools`, so its own object keeps its last status; these
  counts are the signal that it is gone from DSM's working set.
- `system_partition_crashed`: `env.status.system_crashed` (DSM:
  Critical).
- `system_partition_needs_repair`: DSM's "repair system partition"
  Warning, as Storage Manager shows it (`showRepairSysPartition`):
  `true` when `env.status.system_need_repair` or `rootbackup_need_repair`
  is `true` and the system partition is not crashed (`system_crashed`),
  not rebuilding (`system_rebuilding`, `rootbackup_rebuilding`) and
  `storage_status` is not `danger`; `false` when one of those holds (DSM
  shows the crash, the rebuild or the Critical verdict instead) or when
  `system_need_repair` is `false` and the root backup flag is not `true`.
  Skipped when no repair flag is `true` and `system_need_repair` is not a
  boolean. DSM 7.3.2 on the lab sends `system_crashed`,
  `system_need_repair` and `system_rebuilding` (all `false`) and no
  `rootbackup_*` keys; a suppressing key DSM does not send does not
  suppress, as in the UI.

The lab NAS reads `normal`, `status_level_normal`, `false`, `0`, `0`, `0`,
`false`, `false`. No degraded value has been seen live yet.

#### NFS Service

| Key | Label | Type | Unit | Monitored |
|---|---|---|---|---|
| `nfs_enabled` | NFS Enabled | property | — | — |
| `nfs_v4_enabled` | NFS v4 Enabled | property | — | — |
| `nfs_total_ops` | NFS Total OPS | metric | ops/s | yes |
| `nfs_read_ops` | NFS Read OPS | metric | ops/s | yes |
| `nfs_write_ops` | NFS Write OPS | metric | ops/s | yes |
| `nfs_max_latency` | NFS Max Latency | metric | ms | yes |
| `nfs_client_count` | NFS Client Count | metric | — | yes |

`nfs_max_latency` is DSM's worst NFS latency in the sample
(`SYNO.Core.System.Utilization` `nfs[0].total_max_latency`). DSM reports it
in microseconds; since build 0.0.0.35 (issue #42) the adapter divides by
1000 and publishes milliseconds, the unit the vSphere adapter uses for its
latency metrics, so DSM's 54993 is published as 54.993. It is skipped when
DSM does not send it (before build 35, published as 0). Every build before
0.0.0.35, including v1.0.0.30 and earlier releases, published DSM's value
as sent under the declared unit ms, so history before the upgrade is 1000
times the real latency.

---

### Synology Storage Pool

**Identifiers**: `pool_id` (Pool ID), `diskstation_id` (DiskStation ID; build 34, see [DiskStation identity](#diskstation-identity))

#### Capacity

| Key | Label | Type | Unit | Monitored |
|---|---|---|---|---|
| `total_bytes` | Total | metric | bytes | yes |
| `used_bytes` | Allocated | metric | bytes | yes |
| `usage_pct` | Allocated % | metric | % | yes |

#### Configuration

| Key | Label | Type | Unit | Monitored |
|---|---|---|---|---|
| `raid_type` | RAID Type | property | — | — |
| `status` | Status | property | — | — |
| `pool_path` | Pool Path | property | — | — |
| `device_type` | Device Type | property | — | — |
| `disk_count` | Disk Count | property | — | — |

---

### Synology Volume

**Identifiers**: `volume_id` (Volume ID), `diskstation_id` (DiskStation ID; build 34, see [DiskStation identity](#diskstation-identity))

#### Capacity

| Key | Label | Type | Unit | Monitored |
|---|---|---|---|---|
| `total_bytes` | Total | metric | bytes | yes |
| `free_bytes` | Free | metric | bytes | yes |
| `usage_pct` | Usage % | metric | % | yes |

#### IO

| Key | Label | Type | Unit | Monitored |
|---|---|---|---|---|
| `read_bytes` | Read Throughput | metric | bytes/s | yes |
| `write_bytes` | Write Throughput | metric | bytes/s | yes |
| `read_iops` | Read IOPS | metric | ops/s | yes |
| `write_iops` | Write IOPS | metric | ops/s | yes |
| `utilization_pct` | Utilization | metric | % | yes |

#### Configuration

| Key | Label | Type | Unit | Monitored |
|---|---|---|---|---|
| `volume_path` | Volume Path | property | — | — |
| `fs_type` | Filesystem Type | property | — | — |
| `status` | Status | property | — | — |
| `description` | Description | property | — | — |

#### Cache

| Key | Label | Type | Unit | Monitored |
|---|---|---|---|---|
| `cache_enabled` | Cache Enabled | property | — | — |
| `cache_status` | Cache Status | property | — | — |
| `cache_read_hit_rate` | Cache Read Hit Rate | metric | % | yes |
| `cache_write_hit_rate` | Cache Write Hit Rate | metric | % | yes |

---

### Synology Disk

**Identifiers**: `disk_id` (Disk ID), `diskstation_id` (DiskStation ID; build 34, see [DiskStation identity](#diskstation-identity))

#### Health

| Key | Label | Type | Unit | Monitored |
|---|---|---|---|---|
| `temperature` | Temperature | metric | C | yes |
| `smart_status` | SMART Status | property | — | — |
| `unc_sectors` | Uncorrectable Sectors | metric | — | yes |
| `remain_life` | Remaining Life | metric | % | yes |

#### IO

| Key | Label | Type | Unit | Monitored |
|---|---|---|---|---|
| `read_bytes` | Read Throughput | metric | bytes/s | yes |
| `write_bytes` | Write Throughput | metric | bytes/s | yes |
| `read_iops` | Read IOPS | metric | ops/s | yes |
| `write_iops` | Write IOPS | metric | ops/s | yes |
| `utilization_pct` | Utilization | metric | % | yes |

#### Hardware

| Key | Label | Type | Unit | Monitored |
|---|---|---|---|---|
| `display_name` | Display Name | property | — | — |
| `model` | Model | property | — | — |
| `firmware` | Firmware | property | — | — |
| `serial` | Serial Number | property | — | — |
| `vendor` | Vendor | property | — | — |
| `disk_type` | Disk Type | property | — | — |
| `disk_code` | Drive Family | property | — | — |
| `is_ssd` | Is SSD | property | — | — |
| `slot_id` | Slot ID | property | — | — |
| `size_bytes` | Size | property | — | — |

---

### Synology iSCSI LUN

**Identifiers**: `lun_uuid` (LUN UUID), `diskstation_id` (DiskStation ID; build 34, see [DiskStation identity](#diskstation-identity))

#### IO

| Key | Label | Type | Unit | Monitored |
|---|---|---|---|---|
| `read_iops` | Read IOPS | metric | ops/s | yes |
| `write_iops` | Write IOPS | metric | ops/s | yes |
| `read_throughput` | Read Throughput | metric | bytes/s | yes |
| `write_throughput` | Write Throughput | metric | bytes/s | yes |
| `read_latency` | Read Latency | metric | ms | yes |
| `write_latency` | Write Latency | metric | ms | yes |

`read_latency` and `write_latency` are DSM's `read_avg_latency` and
`write_avg_latency` from `SYNO.Core.System.Utilization` `lun[]`. DSM reports
them in microseconds: its own Resource Monitor charts these fields as
microseconds and shows 2188 as "2.2 ms". Since build 0.0.0.35 (issue #42)
the adapter divides by 1000 and publishes milliseconds (the vSphere
adapter's `virtualDisk|totalReadLatency_average` convention), so 2188 is
published as 2.188. Builds 0.0.0.32 to 0.0.0.34 published DSM's value as
sent, 1000 times too large; earlier builds published 0.

#### Configuration

| Key | Label | Type | Unit | Monitored |
|---|---|---|---|---|
| `name` | Name | property | — | — |
| `size_bytes` | Size | property | — | — |
| `location` | Location | property | — | — |
| `type` | Type | property | — | — |
| `target_iqn` | Target IQN | property | — | — |
| `target_enabled` | Target Enabled | property | — | — |
| `network_portals` | Network Portals | property | — | — |

---

### Synology NFS Export

**Identifiers**: `share_name` (Share Name), `diskstation_id` (DiskStation ID; build 34, see [DiskStation identity](#diskstation-identity))

#### Capacity

| Key | Label | Type | Unit | Monitored |
|---|---|---|---|---|
| `size_used_mib` | Size Used | metric | MiB | yes |
| `size_logical_mib` | Size Logical | metric | MiB | no |
| `quota_usage_pct` | Quota Usage % | metric | % | yes |

#### Clients

| Key | Label | Type | Unit | Monitored |
|---|---|---|---|---|
| `active_client_count` | Active Client Count | metric | — | yes |

#### Configuration

| Key | Label | Type | Unit | Monitored |
|---|---|---|---|---|
| `export_path` | Export Path | property | — | — |
| `volume_path` | Volume Path | property | — | — |
| `description` | Description | property | — | — |
| `quota_value_mib` | Quota Value | property | — | — |

#### Access Control

| Key | Label | Type | Unit | Monitored |
|---|---|---|---|---|
| `cow_enabled` | CoW Enabled | property | — | — |
| `compress_enabled` | Compression Enabled | property | — | — |
| `rule_count` | Rule Count | property | — | — |
| `allowed_clients` | Allowed Clients | property | — | — |

---

### Synology UPS

**Identifiers**: `ups_model` (UPS Model), `diskstation_id` (DiskStation ID; build 34, see [DiskStation identity](#diskstation-identity))

#### Battery

| Key | Label | Type | Unit | Monitored |
|---|---|---|---|---|
| `charge_pct` | Charge | metric | % | yes |
| `runtime_seconds` | Runtime | metric | sec | yes |

#### Status

| Key | Label | Type | Unit | Monitored |
|---|---|---|---|---|
| `status` | Status | property | — | — |
| `mode` | Mode | property | — | — |
| `connected` | Connected | property | — | — |

---

### Synology SSD Cache

**Identifiers**: `cache_id` (Cache ID), `diskstation_id` (DiskStation ID; build 34, see [DiskStation identity](#diskstation-identity))

#### Hit Rate

| Key | Label | Type | Unit | Monitored |
|---|---|---|---|---|
| `read_hit_rate` | Read Hit Rate | metric | % | yes |
| `write_hit_rate` | Write Hit Rate | metric | % | yes |

#### Capacity

| Key | Label | Type | Unit | Monitored |
|---|---|---|---|---|
| `total_bytes` | Total | metric | bytes | yes |
| `occupied_bytes` | Occupied | metric | bytes | yes |
| `reusable_bytes` | Reusable | metric | bytes | no |
| `memory_used` | Metadata Memory | metric | bytes | no |

#### Configuration

| Key | Label | Type | Unit | Monitored |
|---|---|---|---|---|
| `mode` | Mode | property | — | — |
| `status` | Status | property | — | — |
| `mount_volume` | Mount Volume | property | — | — |
| `device_type` | RAID Type | property | — | — |
| `skip_seq_io` | Skip Sequential IO | property | — | — |

#### Hardware

| Key | Label | Type | Unit | Monitored |
|---|---|---|---|---|
| `disk_count` | Disk Count | property | — | — |
| `disk_members` | Disk Members | property | — | — |
| `total_capacity` | Total Capacity | property | — | — |
| `disk_failure_count` | Disk Failure Count | property | — | — |

`disk_failure_count` is DSM's `disk_failure_number`. It is skipped when DSM
does not send it (build 35; before, a missing count was published as `0`).

---

## Removed objects

Build 35 (framework buildkit 1.0.22): an object DSM no longer reports is
marked **Not Existing** after it has been missing from 12 consecutive
complete collection cycles (an hour at the default 5-minute interval), the
way the vSphere, vSAN and NSX adapters mark a deleted object. VCF Operations
then stops collecting it, shows it as Not Existing (health grey) and deletes
it, with its history, after the global **Deleted Objects** retention setting
(168 hours by default). The pack declares a kind read in full only when
nothing about its read failed that cycle, and only a kind read in full
counts a miss (`ReadKinds`):

| Kinds | Counted only when |
|---|---|
| World | the DiskStation is counted (the World is delivered from the same read; only a World not delivered, the pre-build-34 `Synology World`, takes a miss) |
| Diskstation | `SYNO.Core.System.SystemHealth` was read too (the DiskStation itself comes from `SYNO.DSM.Info`, which every cycle needs) |
| Storage Pool, Volume, Disk | storage `load_info` was complete: pools, volumes and disks each a list, at least one disk, every pool, volume and disk with its id (a volume's `volume_id`, else `vol_path`), and `missing_pools` and `detected_pools` both sent as lists and both empty. An empty pool or volume list counts only when both are empty and every disk states a `used_by` that is empty or a listed shared cache (no disk is in a pool). Pools also need every volume's `pool_path` to name a listed pool, and every disk's `used_by` to be empty, a listed pool or a listed shared cache. None of the three counts while `overview_data.space_affect_by_eunit_lost` is `true` or `incompatible_eunit` is non-empty (each when sent) |
| SSD Cache | `load_info` sent `ssdCaches` as a list with an `id` on every cache, every volume's `cache.id` names a listed cache, no `sharedCaches` entry is `is_missing: true`, and no pool is missing or detected (as above). An empty list counts only when `sharedCaches` is an empty list, no volume has a `cache.id`, and every disk states a `used_by` that is empty or a listed pool |
| iSCSI LUN | `SYNO.Core.ISCSI.LUN` was read and sent `luns` as a non-empty list with a `uuid` on every LUN, and no pool is missing or detected |
| NFS Export | `SYNO.Core.Share` was read as a non-empty list with a name on every share and a `total` (when sent) equal to the shares returned, the NFS service was not reported off, no share's NFS rule probe failed (an answer with no rule list counts as failed), at least one share is an export, and no pool is missing or detected |
| UPS | `SYNO.Core.ExternalDevice.UPS` answered with `usb_ups_connect`, and with the `model` when a UPS is connected; an answer of no UPS connected counts the old UPS as gone |

**An empty answer is not proof that everything is gone.** An empty list
counts a miss only when another field DSM sends shows the kind really is
empty (the evidence in the table); otherwise it counts nothing. No such
field is known for LUNs, NFS exports, or volumes on a pool that still
exists, so **the last object of those kinds is never removed
automatically**: after deleting the last LUN, turning off NFS on the last
exported share, or deleting the last volume of a pool, delete its object by
hand. The same holds for the last pool or SSD cache when a disk does not
state its `used_by` (DSM's value for an unused disk is not yet seen live).
A DSM error inside an HTTP 200 (`success: false`) is a failed read, never an
empty one: the endpoint is unread and its kind counts nothing.

**A missing pool is not a deleted pool.** DSM's Storage Manager lists pools
as `storagePools` plus `detected_pools` plus `missing_pools`
(`PoolModule.loadPool` in DSM's `storage_panel.js`). A pool whose drives are
absent (an expansion unit unplugged or without power, drives pulled) moves
to `missing_pools`, and one waiting for Online Assemble to `detected_pools`;
either leaves `storagePools`, and its volumes and the absent disks may leave
theirs. So while either key holds a pool, or DSM sent neither key as a list,
no Storage Pool, Volume, Disk, SSD Cache, iSCSI LUN or NFS Export counts a
miss (each can live on that pool); DiskStation and UPS still do. The same
holds while `overview_data` says an expansion unit was lost
(`space_affect_by_eunit_lost` `true`) or is incompatible (`incompatible_eunit`
non-empty). The pool's object stays, logs `absent from snapshot` each cycle
and keeps the status it last had, so Storage Pool Not Healthy does not fire
for it: DSM's status value for a pool in `missing_pools` is not yet seen
(the lab NAS has none), and the pack publishes no value it has not seen.
The DiskStation carries the signal instead: `Health|missing_pools`,
`Health|storage_status`, `Health|expansion_unit_lost` (see Health above).
The same holds while a disk's `used_by` names neither a listed pool, a
listed shared cache, a listed SSD cache (`ssdCaches[].id`) nor a hot spare
(`hotspare`): no volume names that pool, so what lived on it may have left
every list with it, and no Storage Pool, Volume, Disk, SSD Cache, iSCSI LUN
or NFS Export counts a miss. The same "pointer to an object the list omits"
rule covers a volume whose `pool_path` names an unlisted pool (no pool
counted: the volume on it is still listed, so DSM has not dropped what
lives on that pool), and a volume whose `cache.id` names an unlisted cache or a
`sharedCaches` entry with `is_missing: true` (no SSD cache counted). The share list pages (`limit=2` returns 2 rows of `total`
20 on the lab NAS); a `total` other than the rows returned counts no export.

**An unknown disk `used_by` keeps storage removal off on that NAS.** The
accepted values come from DSM's `storage_panel.js`: a hot spare is
`hotspare` (`isHotspare`, `notUsedBySpace`), and a disk in a non-shared
SSD cache names a space id of type `ssd`, the same type as that cache's
own id (`isSingleCache`, `relatedPoolArr`), so a `used_by` equal to a
listed `ssdCaches[].id` is accepted. Neither has been seen live (the lab's
disks name `reuse_1` or `shared_cache_1`; its only SSD cache,
`alloc_cache_1_1`, sits on the shared cache). Any other value (a cache id
in a form that is not exactly the listed id, `system`, a value from a newer
DSM) counts no Storage Pool, Volume, Disk, SSD Cache, iSCSI LUN or NFS
Export miss for as long as a disk carries it, with no log line; those
objects are then never marked, never deleted, on that NAS.

So one share whose NFS probe failed counts no export miss, however long it
lasts, nor does a failed UPS, LUN or System Health read for its kind; the
count resumes when the read is complete again. While the NFS service is off,
an export is ERROR, not gone, and is not counted. A NAS without SAN Manager
sends no LUN list (DSM error 102), so its LUNs are not counted either; delete
leftover LUN objects by hand after removing SAN Manager. A cycle whose
snapshot failed (a required endpoint, or the DiskStation identity) counts
nothing at all. No kind is excluded (`notExistingExcludedKinds` is empty):
the World is counted with the DiskStation, and the current World is
registered first in every enumeration, so only a World the cycle did not
deliver, the one from before build 34, takes a miss. The counts are
held in memory, so a collector restart delays a mark and never makes one
early. A marked object that comes back is registered again by discovery;
that path is not yet seen live (framework note,
`knowledge/context/sdk/vanished_object_lifecycle.md` in the factory).

**First upgrade to build 35:** every object registered under a key the pack
no longer produces is counted from the first cycle. On devel_9_1 and
devel_8_18 (2026-10-28) that is 29 pre-build-34 objects with no
`diskstation_id` each: 16 NFS exports, 7 disks, 2 iSCSI LUNs, 1 storage
pool, 1 volume, 1 SSD cache and the old `Synology World`, all 29 marked
about 12 cycles after the upgrade. Once the old World is deleted, the
tree's duplicate root goes away (the DiskStation was a child of both
Worlds).

---

## Traversal Spec

**Name**: Synology DiskStation Storage Tree

```
Synology World
    └── Synology Diskstation
        ├── Synology Storage Pool
        │   ├── Synology Volume
        │   │   ├── Synology iSCSI LUN
        │   │   ├── Synology NFS Export
        │   │   └── Synology SSD Cache
        │   │       └── Synology Disk
        │   └── Synology Disk
        └── Synology UPS
```

The spec is rooted at the World kind (`rootResourceKind="SynologyWorld"`,
each path starting
`synology_diskstation::SynologyWorld||synology_diskstation::SynologyDiskstation::child||...`),
as VMware's own vSphere adapter roots "vSphere Hosts and Clusters" at
`VMWARE::vSphere World`. Up to build 34 it was rooted at the adapter instance
kind; the instance resource has no children (its only parent is Universe), so
the tree showed the instance and nothing under it (devel and devel_9_1,
2026-10-28). The adapter instance is not linked to the World.

## Bundled alerts

Build 35 (issue #35). All nine are enabled in the default policy after
install. Every symptom waits 3 cycles to trigger and 3 to cancel. A status
the adapter could not read is not published, so no symptom fires on a
missing value. Symptom and alert names carry the `[VCF Content Factory]
Synology` prefix in VCF Operations; the table drops it. The status
symptoms use the REGEX operator, so only the DSM values listed below
raise them.

| Alert | Object | Symptoms | Symptom condition | Severity | On by default |
|---|---|---|---|---|---|
| Storage Pool Not Healthy | Storage Pool | Storage Pool Failed | `Configuration\|status` matches `^(degrade\|crashed\|deverr)$` | Critical | yes |
| Volume Not Healthy | Volume | Volume Failed | `Configuration\|status` matches `^(degrade\|crashed\|deverr\|ro)$` | Critical | yes |
| Volume Nearly Full | Volume | Volume Usage Warning / Volume Usage Critical | `Capacity\|usage_pct` >= 85 / >= 95 | Warning / Critical | yes |
| Disk Failing | Disk | Disk SMART Failing / Disk Uncorrectable Sectors Increasing | `Health\|smart_status` matches `^(failing\|degraded\|access_err)$` / `Health\|unc_sectors` above its dynamic threshold | Critical / Warning | yes |
| SSD Cache Failure | SSD Cache | SSD Cache Disk Failure / SSD Cache Failed | `Hardware\|disk_failure_count` != `0` / `Configuration\|status` matches `^(degrade\|crashed\|deverr\|ro)$` | Critical | yes |
| Fan Failure | DiskStation | Fan Failed | `Fan\|fan_health` = `failed` | Critical | yes |
| UPS On Battery | UPS | UPS On Battery / UPS Low Battery | `Status\|status` = `usb_ups_status_battery` / = `usb_ups_status_low_batt` | Warning / Critical | yes |
| Storage Degraded | DiskStation | Storage Critical / Storage Warning / Storage Pool Missing / Storage Pool Awaiting Assembly / System Partition Crashed / System Partition Needs Repair (any one) | `Health\|storage_status` = `danger` / = `attention` / `Health\|missing_pools` != `0` / `Health\|detected_pools` != `0` / `Health\|system_partition_crashed` = `true` / `Health\|system_partition_needs_repair` = `true` | Critical / Warning / Critical / Warning / Critical / Warning | yes |
| Expansion Unit Lost | DiskStation | Expansion Unit Lost **and** Storage Critical / Incompatible Expansion Unit | `Health\|expansion_unit_lost` = `true` and `Health\|storage_status` = `danger` / `Health\|incompatible_expansion_units` != `0` | Critical / Warning | yes |

Value sources (DSM 7.3.2 UI code on the lab DS1520+, 2026-10-28): pool and
volume status keys are DSM's `volume_status_*` string table (`degrade`
Degraded, `crashed` Crashed, `deverr` Unknown Error, `ro` Read Only).
The pool and volume patterns leave out `pool_crashed` and `pool_error`,
because those values never appear in `Configuration|status`: DSM keeps them
in the object's `space_status.status` (Storage Manager's
`isAllowedToEject` and `isPotentialCrashed` read them there), a separate
field whose values carry a prefix (`pool_normal` on a healthy pool,
`fs_normal` on a healthy volume, `cache_normal` on a healthy SSD cache;
lab DS1520+, DSM 7.3.2, 2026-10-28), while `status` reads `normal`. The
adapter publishes the top-level `status` only, so a pattern for those two
values could never match. Storage Manager reads a crashed pool from the top-level
field (`isAllocStatCrashed`: `"crashed"===this.status`). No failure value
has been seen live on a failing pool or volume. Not failures, so they raise
nothing: `normal` and the in-progress states (`background`, `repair`,
`resync`, `raid_sync`, `scrubbing`, `expand`, `expand_fs`, `add_disk`,
`create`, `creating`, `create_fs`, `delete`, `deleting`, `deactivating`,
`defrag`, `optimize`, `paritycount`, `running_fsck`,
`running_btrfs_balance`, `upgrade`, `replace`, `waiting`, `activating`,
`initializing`). SMART: `normal`; `failing`, `degraded` and `access_err`
(Storage Manager: "Replace the drive immediately") are failures;
`unknown` means DSM cannot read the drive's SMART and raises nothing. UPS:
DSM's `ups` string table (`usb_ups_status_online` Connected,
`usb_ups_status_battery` On Battery, `usb_ups_status_low_batt` Low
Battery, `usb_ups_status_off` Off; `usb_ups_status_unknown` with no UPS),
not yet seen on a live UPS. Storage Degraded and Expansion Unit Lost:
the DiskStation properties under Health above, from storage `load_info`,
read as DSM Storage Manager's health card reads them (`storage_panel.js`,
DSM 7.3.2): `storage_status` `danger` is Critical and `attention` Warning
(`isLevelDanger`, `isLevelAttention`), a non-empty `incompatible_eunit`
is a Warning row, `system_crashed` a Critical row, and
`system_partition_needs_repair` is the UI's "repair system partition"
Warning row. Expansion Unit Lost's first set needs both symptoms because
the UI uses `space_affect_by_eunit_lost` only together with a danger level
(`isSpaceIssueByMultipath && isLevelDanger(...)`); that danger level also
raises Storage Degraded. Not seen degraded live: the lab DS1520+ reads
`normal`, `false`, `0` everywhere (2026-10-28).
