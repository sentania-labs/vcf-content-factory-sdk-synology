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
  properties and is logged once at INFO. VCF Operations still shows it as
  Data Receiving with its last values frozen until it is deleted. Delete
  it; see the README section Upgrading.

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

Build 35: DSM's System Health widget verdict (`SYNO.Core.System.SystemHealth`).
`system_health` is `normal`, `attention` or `danger` (DSM rule type 2, 1,
0); `system_health_rule` is DSM's rule id (`system_health`,
`ups_status_abnormal`, `disk_health_status_error`, ...);
`system_health_message` is the DSM string-table key of the message
(`widget:system_ok` when healthy). Each is skipped when DSM did not send it.

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

Build 35 (issue #35). All seven are enabled in the default policy after
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
not yet seen on a live UPS.
