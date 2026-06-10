# Changelog

## 1.0.0.14 (2026-06-10)

- feat(adapter): framework v2 migration — port from v1 (aria-ops-core /
  com.vmware.tvs.*) to the com.vcfcf.adapter.spi roles. SynologyAdapter now
  extends VcfCfAdapter-on-AdapterBase and implements VcfCfTester /
  VcfCfDiscoverer / VcfCfCollector; v1 LiveCollector / Discoverer / Tester and
  the aria-ops-core Resource/ResourceCollection model are dropped.
- feat(adapter): per-resource collect over a per-cycle API snapshot. v2 calls
  collect(rc) once per discovered resource; a Snapshot caches the DSM API pull
  for the cycle and each collect dispatches on resource kind, preserving v1's
  single-pull value semantics (parity bar: 25 resources / 136 metrics).
- feat(adapter): self-contained tester — derives host/credentials from the
  TestParam ResourceConfig so Test-connection works on a bare instance
  (configureAdapter has not run). Mirrors compliance build-46.
- fix(adapter): drop foreign-resource (Datastore) stitching. v1 produced no
  landing data (golden baseline §3); ForeignResourceResolver/stitchDatastores
  removed; no SuiteApiStitcher added.
- fix(adapter): SynologyApiClient logger is now the framework instance Logger
  via componentLogger(SynologyApiClient.class) (was java.util.logging, whose
  records never reached the adapter log) — framework_v2_migration.md §15.
- fix(adapter): constructors call super(ADAPTER_KIND) /
  super(ADAPTER_KIND, dir, id); onDescribe() is the framework default (no
  hand-rolled override) — lessons/controller-describe-bare-instantiation.md.
- chore(adapter): C2 pak shape — lib/ = vcfcf-adapter-base.jar only;
  aria-ops-core and vrops-adapters-sdk omitted (no com.vmware.tvs.* in
  compiled classes).

## 1.0.0.13 (2026-05-20)

- feat(knowledge): Implement Phase 2 knowledge layer architecture
- feat(unifi): UniFi Controller SDK adapter build 2 + auto-doc generation
- docs(synology): update CHANGELOG through build 13
- feat(synology): build 13 — properties fix, World object, UX polish

## 1.0.0.9 (2026-05-19)

- release(synology): flag adapter as released (v1.0)
- feat(synology): build 9 — SSD cache, traversal spec, version string

## 1.0.0.8 (2026-05-19)

- feat(sdk-framework): build 8 — framework helpers + Datastore stitching

## 1.0.0.7 (2026-05-19)

- feat(synology): internal relationships + ARIA_OPS Datastore stitching

## 1.0.0.6 (2026-05-19)

- feat(sdk-framework): build 6 — discovery, logging, DNS retry

## 1.0.0.5 (2026-05-19)

- debug(synology): add System.err diagnostic logging in configure()

## 1.0.0.4 (2026-05-19)

- fix(sdk-framework): use platform Logger, not java.util.logging

## 1.0.0.3 (2026-05-19)

- fix(sdk-framework): add no-arg constructor for analytics describe()

## 1.0.0.2 (2026-05-19)

- feat(sdk-builder): custom icon set + icon packaging for Tier 2 paks

## 1.0.0.1 (2026-05-19)

- fix(sdk-framework): add required (String, Integer) constructor
- feat(synology): Tier 2 SDK adapter — first compilable build
