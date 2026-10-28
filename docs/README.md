# VCF Content Factory Synology DiskStation Documentation

> Generated index. The SVG diagram and per-kind table are regenerated on every
> build; prose sections (overview, installing) are hand-curated.

## Contents

| Section | Description |
|---------|-------------|
| [Overview](overview.md) | What's in the pack, resource kinds, cross-adapter notes |
| [Installing & Configuring](installing.md) | Prerequisites, configuration fields, step-by-step guide |
| [Inventory Tree](inventory-tree.md) | Traversal spec, per-kind table with identifying keys |
| [Metrics Reference](../REFERENCE.md) | Full metrics and properties reference (generated) |

## Inventory Tree

![Inventory Tree](inventory-tree.svg)

## Cross-MP Relationships

These edges are created at collection time via the Suite API and never appear in `describe.xml`. They are declared explicitly in `adapter.yaml` (`cross_mp_edges`) so this generated docset doesn't silently omit them. *Italic* endpoints belong to a foreign management pack; `code` endpoints are owned by this adapter.

| Parent | Child | Description |
|--------|-------|-------------|
| *VMWARE Datastore* (foreign, VMWARE) | `SynologyIscsiLun` | iSCSI LUN attached under the backing VMWARE Datastore (matched by computed VMFS extent NAA path); additive parentForeign edge via Suite API, resolved against real inventory only (no phantom Datastore minted when no match exists). One path can back N datastores (one per vCenter view) — bound to every copy. |
| *VMWARE Datastore* (foreign, VMWARE) | `SynologyNfsExport` | NFS export attached under the backing VMWARE Datastore (matched by computed `<nas_ip>/<vol_path>/<share>` path per connected NAS interface); additive parentForeign edge via Suite API, deduped so a single Datastore never gets the same export as a duplicate child. |

## Quick Reference

- **Adapter kind:** `synology_diskstation`
- **Version:** 0.0.0.35
- **Traversal spec:** Synology DiskStation Storage Tree
- **Resource kinds:** 9
- **Cross-MP relationships:** 2 (see [Cross-MP Relationships](#cross-mp-relationships) below)

## Adapter-Specific Documentation

<!-- Merged from docs/_index_extra.md; edit that file, not this section. -->

### Alerts

The pak ships seven alerts with their symptoms and recommendations (build
35): Storage Pool Not Healthy, Volume Not Healthy, Volume Nearly Full,
Disk Failing, SSD Cache Failure, Fan Failure and UPS On Battery. What each
fires on, its object and severity: [REFERENCE.md, Bundled alerts](../REFERENCE.md#bundled-alerts).
