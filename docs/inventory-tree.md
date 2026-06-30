# Inventory Tree — VCF Content Factory Synology DiskStation

> Generated from `describe.xml` v1.0.0.20. Do not edit — regenerated on every build.

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
