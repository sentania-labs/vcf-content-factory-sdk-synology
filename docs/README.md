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

### Removed objects are deleted (build 35)

An object DSM no longer reports is marked Not Existing after 12
consecutive complete collection cycles (an hour at 5 minutes), and VCF
Operations deletes it, with its history, alerts and group membership, after
the Deleted Objects retention (168 hours by default). This cannot be
undone, and the first upgrade does it at once for every object from before
build 34. A kind counts a miss only in a cycle that read it in full: a
failed read counts nothing, nor does an empty list without DSM's own
evidence, nor any storage kind while DSM reports a pool missing or awaiting
assembly (an expansion unit unplugged, drives pulled). The last LUN, NFS
export and volume on a pool are removed by hand. Rules and edge cases:
[REFERENCE.md, Removed objects](../REFERENCE.md#removed-objects); upgrade
note: [README.md, To build 35](../README.md#to-build-35-objects-dsm-no-longer-reports-are-deleted-and-the-first-upgrade-deletes-the-pre-build-34-leftovers).

### Alerts

The pak ships nine alerts with their symptoms and recommendations (build
35): Storage Pool Not Healthy, Volume Not Healthy, Volume Nearly Full,
Disk Failing, SSD Cache Failure, Fan Failure, UPS On Battery, Storage
Degraded and Expansion Unit Lost. What each
fires on, its object and severity: [REFERENCE.md, Bundled alerts](../REFERENCE.md#bundled-alerts).
