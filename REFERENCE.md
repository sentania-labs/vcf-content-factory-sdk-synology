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

## Object Types

### Synology World

**Identifier**: `world_id` (World ID)

---

### Synology Diskstation

**Identifier**: `serial` (Serial Number)

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
| `fan_status` | Fan Status | property | — | — |
| `fan_speed_mode` | Fan Speed Mode | property | — | — |

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

---

### Synology Storage Pool

**Identifier**: `pool_id` (Pool ID)

#### Capacity

| Key | Label | Type | Unit | Monitored |
|---|---|---|---|---|
| `total_bytes` | Total | metric | bytes | yes |
| `used_bytes` | Used | metric | bytes | yes |
| `usage_pct` | Usage % | metric | % | yes |

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

**Identifier**: `volume_id` (Volume ID)

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

**Identifier**: `disk_id` (Disk ID)

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

**Identifier**: `lun_uuid` (LUN UUID)

#### IO

| Key | Label | Type | Unit | Monitored |
|---|---|---|---|---|
| `read_iops` | Read IOPS | metric | ops/s | yes |
| `write_iops` | Write IOPS | metric | ops/s | yes |
| `read_throughput` | Read Throughput | metric | bytes/s | yes |
| `write_throughput` | Write Throughput | metric | bytes/s | yes |
| `read_latency` | Read Latency | metric | ms | yes |
| `write_latency` | Write Latency | metric | ms | yes |

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

**Identifier**: `share_name` (Share Name)

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

**Identifier**: `ups_model` (UPS Model)

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

**Identifier**: `cache_id` (Cache ID)

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

---

## Traversal Spec

**Name**: Synology DiskStation Storage Tree

```
synology_diskstation
    └── Synology World
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
