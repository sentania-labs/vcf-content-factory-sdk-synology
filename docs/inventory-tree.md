# Inventory Tree — VCF Content Factory Synology DiskStation

> Generated from `describe.xml` v1.0.0.30. Do not edit — regenerated on every build.

**Traversal Spec:** Synology DiskStation Storage Tree

## Traversal Tree

- **Synology DiskStation** (`synology_diskstation`)
  - **Synology World** (`SynologyWorld`)
    - **Synology Diskstation** (`SynologyDiskstation`)
      - **Synology Storage Pool** (`SynologyStoragePool`)
        - **Synology Volume** (`SynologyVolume`)
          - **Synology iSCSI LUN** (`SynologyIscsiLun`)
            - **Datastore** (`Datastore`)
          - **Synology NFS Export** (`SynologyNfsExport`)
          - **Synology SSD Cache** (`SynologySsdCache`)
            - **Synology Disk** (`SynologyDisk`)
      - **Synology UPS** (`SynologyUps`)

> \* = identifying (unique) key

## Resource Kinds Reference

| Kind | Display Label | Identifying Keys | Parent(s) |
|------|--------------|-----------------|-----------|
| `SynologyWorld` | Synology World | `world_id` * | Synology DiskStation |
| `SynologyDiskstation` | Synology Diskstation | `serial` * | Synology World |
| `SynologyStoragePool` | Synology Storage Pool | `pool_id` * | Synology Diskstation |
| `SynologyVolume` | Synology Volume | `volume_id` * | Synology Storage Pool |
| `SynologyDisk` | Synology Disk | `disk_id` * | Synology SSD Cache, Synology Storage Pool |
| `SynologyIscsiLun` | Synology iSCSI LUN | `lun_uuid` * | Synology Volume |
| `SynologyNfsExport` | Synology NFS Export | `share_name` * | Synology Volume |
| `SynologyUps` | Synology UPS | `ups_model` * | Synology Diskstation |
| `SynologySsdCache` | Synology SSD Cache | `cache_id` * | Synology Volume |

## Cross-MP Relationships

These edges are created at collection time via the Suite API and never appear in `describe.xml` — they are declared explicitly in `adapter.yaml` (`cross_mp_edges`) so this generated docset doesn't silently omit them. *Italic* endpoints belong to a foreign management pack; `code` endpoints are owned by this adapter.

| Parent | Child | Description |
|--------|-------|-------------|
| *VMWARE Datastore* (foreign, VMWARE) | `SynologyIscsiLun` | iSCSI LUN attached under the backing VMWARE Datastore (matched by computed VMFS extent NAA path); additive parentForeign edge via Suite API, resolved against real inventory only (no phantom Datastore minted when no match exists). One path can back N datastores (one per vCenter view) — bound to every copy. |
| *VMWARE Datastore* (foreign, VMWARE) | `SynologyNfsExport` | NFS export attached under the backing VMWARE Datastore (matched by computed `<nas_ip>/<vol_path>/<share>` path per connected NAS interface); additive parentForeign edge via Suite API, deduped so a single Datastore never gets the same export as a duplicate child. |
