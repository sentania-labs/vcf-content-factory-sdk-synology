# Changelog

## 0.0.0.25 (2026-07-02)

- feat(synology): build 25 — **EXPERIMENT for DEF-006**
  (`context/defects.md` DEF-006; design-of-record:
  `designs/sdk-adapters/synology-declarative-stitch-experiment.md`). Tests
  whether the platform's property-driven relationship mechanism —
  live-proven CP-immune for Oracle
  (`relationships|VirtualMachine_parent = oracledemo`, zero Suite API
  calls, recon 2026-07-02) — also binds a `VMWARE::Datastore` from a
  **path-valued** property, giving Synology a zero-credential stitch on a
  Cloud Proxy. Two changes, both additive; **the runtime credentialed
  stitcher (`SynologyStitcher`, `emitDatastoreCrossLink`) is untouched** —
  it stays the isolation control, dead on the prod CP (DEF-006, 401 every
  cycle), so any new edge there is attributable only to the property
  mechanism:
  1. **describe.xml:** added a `relationships` `ResourceGroup` +
     `Datastore_parent` `isProperty="true"` `ResourceAttribute` to both
     `SynologyIscsiLun` and `SynologyNfsExport`, cloned verbatim from the
     TVS storage-pak construct quoted in
     `context/api-maps/tvs-declarative-stitching.md` — e.g. PureStorage
     FlashArray 4.3.0 `describe.xml` line 225: `<ResourceAttribute
     key="Datastore_parent" nameKey="81" dataType="string"
     isProperty="true" defaultMonitored="true" isDiscrete="false"
     keyAttribute="false" isRate="false" hidden="false">
     </ResourceAttribute>`, same attribute set. The `||VMWARE::Datastore::
     ~child` traversal hop this placeholder pairs with already existed in
     this adapter since build 18 (`TraversalSpecKinds`, unchanged this
     build). New nameKeys 285-288 added to `resources/resources.properties`
     (max prior was 302; no collision).
  2. **SynologyAdapter.java:** `collectIscsiLun` and `collectNfsExport` now
     each report a string property `relationships|Datastore_parent` valued
     with the *same* VMFS/NFS path `SynologyStitcher` already computes for
     its own `DataStrorePath` match — `SynologyStitcher.lunDataStorePath
     (uuid)` (`"VMFS:|naa.<lun-naa>|"`) for LUNs,
     `SynologyStitcher.nfsDataStorePath(nasIp, volPath, shareName)`
     (`"<nas-ip>/<export-path>"`, first connected NAS IP) for NFS exports.
     Both helpers are pre-existing (build 16); build 25 adds no new value
     construction, only a new consumer of the existing one. Zero Suite API
     involvement in producing these values — both are pure functions of
     data already collected from the Synology DSM API. Property-key
     convention (`relationships|Datastore_parent`) verified against
     Oracle's live property `relationships|VirtualMachine_parent =
     oracledemo` (recon 2026-07-02, quoted in the design doc) — same
     `relationships|<Kind>_parent` shape, `Datastore` substituted for
     `VirtualMachine` per the describe.xml placeholder's attribute key.
  - Devel/prod test build — no `v*` release tag. Per RULE-014
    (`rules/pak-version-lines.md`) this hand build is expected to stamp
    `0.0.0.25`.

## 0.0.0.24 (2026-07-01)

- fix(framework): build 24 — **no adapter source change**. describe.xml,
  resources, Java source, metric/property keys, resource kinds, identifiers,
  relationships, and contract-assert behavior are byte-unchanged vs build 23;
  the only delta is the version string and a recompile against the updated
  framework `adapter_framework/` bundled by `build-sdk`. This build inherits the
  **DEF-005 transport fix** (`context/defects.md` DEF-005) on top of build 23's
  identity + additive-verb fixes. DEF-005: the strict-TOFU loopback Suite API
  transport (PRs #29/#30, latent in every build ≥ 20) PKIX-failed every cycle on
  live devel — the platform's non-disruptive cert handler never persists trust
  for framework adapters ("renewal url set is empty"), so `loadDatastores`
  failed forever and the cross-MP datastore stitch read zero datastores. The fix
  (review-approved: `context/reviews/framework/bc-mirror-transport-v1.md` +
  `-v2.md`) makes the loopback Suite API hop **mirror the Broadcom vendor
  `aria-ops-core SuiteAPIClient` transport exactly** — trust-all +
  ignore-hostname on the non-FIPS branch; FIPS-approved-only mode logs a
  once-per-adapter-instance WARN and falls through to the non-FIPS mirror. This
  is a framework-jar-only change: the bundled `vcfcf-adapter-base.jar` now
  carries `VcfCfAdapter.applyBcMirrorTransport` + the `fipsGapWarnLogged` gate,
  and the old strict-path machinery is removed from `openPlatformConnection`.
  The build-23 fixes are preserved intact: ambient identity (CP-403 —
  `AmbientCredential` automation-first + `getSourceLabel()`) and additive
  foreign-parent edges (`RelationshipBuilder`). Devel/prod test build — no `v*`
  release tag. Per RULE-014 (`rules/pak-version-lines.md`) the hand build stamps
  the `0.x` dev-preview line automatically: this pak is expected to be
  `0.0.0.24` (a `1.0.0.24` would be a guardrail failure). DEF-005 remains open
  and correctly gates `v*` RELEASES only (RULE-012) — it is not a blocker for
  this dev-preview build, and closes on the live-devel collect that loads
  datastores and writes the stitch under the fixed transport.

## 1.0.0.23 (2026-07-01)

- fix(framework): build 23 — **no adapter source change**. describe.xml,
  resources, Java source, metric/property keys, resource kinds, identifiers,
  relationships, and contract-assert behavior are byte-unchanged vs build 22;
  the only delta is the version string and a recompile against the updated
  framework `adapter_framework/` bundled by `build-sdk`. This build exists to
  live-test two inherited stitcher fixes BEFORE the framework PR is opened
  (design-of-record: `designs/stitcher-identity-and-additive-foreign-v1.md`):
  (1) **Ambient identity (CP-403 fix):** `AmbientCredential` now prefers
  `automationuser.properties` (principal `automationAdmin`, RBAC-bearing on
  every node role including a Cloud Proxy) and falls back to
  `maintenanceuser.properties` only when the automation file is absent;
  `SuiteApiStitchClient` INFO-logs the selected `file=automation|maintenance|
  override` plus principal. This fixes the Synology-on-CP stitch that silently
  read nothing as `cloudproxy_<uuid>` (`roles:[]`). (2) **Additive foreign
  parents:** `RelationshipBuilder` emits foreign-parent edges (e.g. onto VMWARE
  Datastore) additively via the delta/add path instead of full-set
  `setRelationships`, matching the Broadcom TVS corpus and remaining clobber-safe
  across platform versions; own-adapter hierarchies keep the consolidated
  one-`setRelationships`-per-parent idiom. Foreign-key identity handling
  (`DataStorePath`, real `isPartOfUniqueness` propagation) is preserved intact.
  Devel/prod test build — no `v*` release tag.

## 1.0.0.22 (2026-06-30)

- fix(adapter): build 22 — review delta on build 21 (explicit Suite API creds).
  Make the spec §4 / ledger-#13 sharp edge loud: when `vrops_username` +
  `vrops_password` are set but `vrops_url` is blank, malformed, or localhost
  (all collapse to a loopback host that 403s on a remote collector), the adapter
  now emits a distinct startup WARN naming the misconfiguration and directing
  the operator to set the URL to the PRIMARY/analytics FQDN — instead of a
  localhost-aimed explicit stitch that *looks* configured while silently reading
  nothing. Loopback is resolved via `InetAddress.isLoopbackAddress()` (new
  `SynologyConfig.suiteApiHostIsLoopback()`), consistent with the framework's
  peer gating, so `127.x` / `::1` and the malformed-URL→localhost degrade are
  all caught. The explicit path is still taken as configured (no silent
  fallback). NITs: `SynologyAdapter` now reuses one hoisted `suiteHost` local
  (used in both the explicit branch and the catch); the unparseable-URL safe
  degrade to localhost is unchanged but is now covered by the new WARN. No
  describe.xml / metric / property / relationship / resource-identifier change;
  blank-config ambient path remains byte-unchanged.

## 1.0.0.21 (2026-06-30)

- feat(adapter): build 21 — optional explicit Suite API credentials for the
  Datastore cross-link on a remote collector / Cloud Proxy. Adds three OPTIONAL
  `required="false"` credential fields to `synology_credentials` —
  `vrops_url`, `vrops_username`, `vrops_password` (password-masked) — grouped
  as "VCF Ops Suite API (remote collector only)". At stitcher construction the
  adapter now branches: when `vrops_username` AND `vrops_password` are both
  non-blank it builds via `SuiteApiStitcher.createExplicit(<primary FQDN parsed
  from vrops_url>, user, pass)` (the only path that serves the global VMWARE
  inventory off-primary — spec 20 §4); otherwise it falls back to the existing
  ambient `create()` (localhost / maintenance user). One startup log line states
  the selected path (ambient vs explicit→host); the password is never logged.
  **Blank config ⇒ ambient ⇒ primary-node behavior is byte-unchanged** vs build
  20 (no resource-identifier change, no metric/property/relationship change).
  Closes the CP/remote-collector 403 gap left by the ambient-only stitch.

## 1.0.0.20 (2026-06-30)

- chore(adapter): build 20 — **no adapter source change**. describe.xml,
  resources, Java source, metric/property keys, resource kinds, identifiers,
  relationships, and contract-assert behavior are byte-unchanged vs build 19;
  the only delta is the version string and a recompile against the fixed
  framework. Recompiles against framework `sdk-buildkit-v1.0.4` to pick up the
  unified Suite API transport fix (loopback hostname-verifier parity +
  CP/remote TOFU+strict; factory PR #30). The Suite API property pusher /
  stitch bridge now negotiates TLS correctly on both the in-VM loopback path
  and the CP/remote path, so cross-MP datastore stitch keeps working under the
  corrected transport. CI/release line continues at `1.0.0.x`.

## 1.0.0.19 (2026-06-26)

- fix(adapter): complete DEF-001 — redact login/collect transport-exception path
  (plaintext password no longer reachable via connect/SSL/timeout failures); NFS
  fan-out child dedup. `callRaw` now wraps `http.get(path, …)` in try/catch so a
  transport failure thrown BEFORE any response (connect/SSL/timeout) rethrows a
  standalone `IOException` built from the `endpoint` label only, with any message
  text `redact()`-scrubbed and NO chained cause (chaining would re-expose
  `getCause().getMessage()` to the framework logger). Closes the residual
  login-path `account=`/`passwd=<plaintext>` leak flagged in build-18 review.
  Also dedups NFS-export children per Datastore so two NAS IPs resolving to the
  same `DataStrorePath` no longer emit `setRelationships(ds, {export, export})`.

## 1.0.0.18 (2026-06-26)

First `1.0.0.x` RELEASE-line build (prod is at `v1.0.0.17`). Consolidates the
cross-MP datastore-stitch work proven on devel (build `0.0.0.22`) into a clean,
gate-ready release. Bundles the same framework base jar
(`vcfcf-adapter-base.jar`, sha256
`4eabad523a30ed547b5aa8987b26d1517dc9d7c89c87a6217ac642ebb3734c53`).

- fix(adapter): DEF-001 — `SynologyApiClient` thrown/logged messages no longer
  carry the request path/query. `callRaw` now builds its `HTTP <code>` message
  from an `api + method` endpoint label only; `login`/`logout`/`call` pass that
  label instead of the `_sid`/`account`/`passwd`-bearing path. The `_sid`-bearing
  logout WARN stays `redact()`-scrubbed. Closes the plaintext-password /
  `_sid`-on-disk exposure (`rules/no-secrets-on-disk.md`).
- fix(adapter): cross-MP foreign Datastore `ResourceKey` now binds — root cause
  was a hardcoded `isPartOfUniqueness=true` on every foreign identifier, which
  built a 4-tuple identity that could not match the real 2-tuple VMWARE Datastore
  key, so the runtime-pushed edge was silently dropped. The bridge now propagates
  each identifier's real uniqueness flag (carried forward from `0.0.0.22`).
- fix(adapter): standardize cross-MP relationship direction to
  `parentForeign(datastore, child)` for BOTH iSCSI LUN and NFS Export — the
  VMWARE Datastore is the parent of its backing LUN/Export (original design
  intent). Reverts the `0.0.0.21` iSCSI experiment that used
  `childForeign(lunKey, ds)`. This is the DEF-003 idiom, which is closed /
  proven-safe: the platform scopes `setRelationships` per-reporting-adapter, so
  the Datastore retains its VMWARE children and only gains the synology child (no
  clobber) — see DEF-003 and `lessons/setrelationships-foreign-adapter-scoped.md`.
- fix(adapter): describe.xml is now symmetric across both storage types — the
  iSCSI foreign path uses the inverse modifier `||VMWARE::Datastore::~child`
  (Datastore = parent) and a matching NFS foreign path was added. These
  declarative paths are UI-navigation only; persistence is driven by the runtime
  push + correct foreign key (devel proof: NFS edges persisted with no
  declarative path at all).
- fix(adapter): multi-datastore fan-out. A shared datastore resolves to N
  `VMWARE/Datastore` objects (one per vCenter view — same NAA / server path,
  distinct `(VMEntityObjectID, VMEntityVCID)`). `matchByPath` now returns ALL
  datastore keys for a path (was one — the single-valued `resolver.loadAll`
  collapsed duplicates), and both the iSCSI and NFS loops emit a `parentForeign`
  edge from each matching datastore. `SynologyStitcher` indexes the Suite API
  bridge entries directly into a `Map<String,List<ResourceKey>>`.

## 0.0.0.22 (2026-06-26)

- fix: foreign Datastore ResourceKey now carries real isPartOfUniqueness flags
  (was hardcoded true → 4-tuple identity couldn't bind to real 2-tuple VMWARE
  Datastore; silent cross-MP edge drop)

## 0.0.0.21 (2026-06-26)

- experiment(adapter): flip cross-MP Datastore edge to owned-LUN-parent →
  foreign-Datastore-child (platform silently filters foreign-parent writes;
  spec-07 one-directional rule). `emitDatastoreCrossLink()` now calls
  `rb.childForeign(lunKey, ds)` instead of `rb.parentForeign(ds, lunKey)` for the
  iSCSI LUN edge, making the owned `SynologyIscsiLun` the PARENT and the foreign
  `VMWARE::Datastore` the CHILD so the edge rides the same persistence path as the
  internal owned-parent tree. describe.xml ResourcePath flipped from the inverse
  `||VMWARE::Datastore::~child` to normal descent `||VMWARE::Datastore::child`.
  SCOPE: iSCSI LUN only — NFS path and `matchByPath`/fan-out untouched. Dev
  preview only, not for release. Bundles the same framework base jar
  (`vcfcf-adapter-base.jar`, sha256
  `4eabad523a30ed547b5aa8987b26d1517dc9d7c89c87a6217ac642ebb3734c53`).

## 0.0.0.20 (2026-06-26)

- experiment(adapter): declare cross-MP `VMWARE::Datastore` ResourcePath to test
  foreign-edge persistence (spec 07). Adds one declarative `<ResourcePath>` inside
  the existing `Synology DiskStation Storage Tree` TraversalSpecKind that extends
  the iSCSI LUN path with a foreign segment `||VMWARE::Datastore::~child`. The
  inverse modifier (`::~child`) reaches the Datastore as the LUN's PARENT, matching
  the runtime `parentForeign(ds, lunKey)` push (Datastore=parent, LUN=child). This
  is a single-variable test of the hypothesis that a runtime-pushed cross-MP edge
  only persists when its shape is also declared in describe.xml. SCOPE: iSCSI LUN
  only — NFS path and `matchByPath`/fan-out untouched; runtime push unchanged. Dev
  preview only, not for release. Bundles the same framework base jar
  (`vcfcf-adapter-base.jar`, sha256
  `4eabad523a30ed547b5aa8987b26d1517dc9d7c89c87a6217ac642ebb3734c53`).

## 0.0.0.19 (2026-06-25)

- chore(adapter): build 19 — dev preview build under the **corrected** hand-build
  version convention. The previous build (18) used `major.minor.patch = 99.0.0`
  to make hand-builds visually distinct, but `99.x` is a defect: a `99.0.0.x` pak
  makes a future real `1.0.0.x` CI release look like a **downgrade**, triggering
  upgrade-refusal on the target. Corrected convention: dev/hand builds set
  `major.minor.patch = 0.0.0` (always strictly below any real release), so this
  pak's version is `0.0.0.19`; the `build_number` counter continues incrementing
  normally (18 → 19). CI/release builds keep the `1.0.0.x` line. No adapter source
  change vs build 18 — describe.xml, resources, Java source, metric/property keys,
  resource kinds, identifiers, relationships, and contract-assert behavior are
  byte-unchanged; the only delta vs build 18 is the version string. Bundles the
  localization-fixed framework base jar (`vcfcf-adapter-base.jar`, sha256
  `4eabad523a30ed547b5aa8987b26d1517dc9d7c89c87a6217ac642ebb3734c53`; carries the
  `AdapterDescribe.make(InputStream)` → `make(String)` swap so describe.xml
  `nameKey`s resolve to localized strings). Not for release — the official
  `1.0.0.x` line is cut by the pak repo's CI on a `v*` tag.

## 99.0.0.18 (2026-06-25)

- chore(adapter): dev preview build. Rebuilt to bundle the localization-fixed
  framework base jar (`vcfcf-adapter-base.jar`, 60252 bytes,
  sha256 4eabad523a30ed547b5aa8987b26d1517dc9d7c89c87a6217ac642ebb3734c53)
  carrying the `onDescribe()` `AdapterDescribe.make(String)` swap. No adapter
  source change; metric/property keys, resource kinds, identifiers,
  relationships, and contract-assert behavior are byte-identical to build 17.
- chore(adapter): version major set to 99 (`99.0.0.18`) per the hand-built
  dev-preview marker convention — keeps locally hand-built paks visually
  distinct from the CI/release `1.0.0.x` line. Not for release.

## 1.0.0.17 (2026-06-10)

- feat(adapter): adopt framework v2 §22 collect-path discovery (build-framework
  task #19). VCF Ops 9.0.2 never invokes onDiscover() for adapter3-path
  collectors, so a fresh Synology instance would heartbeat GREEN yet discover
  zero resources indefinitely. Added `@Override protected boolean
  discoverOnCollect() { return true; }`; extracted the build-16 getDiscoverer()
  enumeration body into `@Override protected void enumerateResources(
  ResourceSink sink)` (dr.addResource -> sink.accept); deleted getDiscoverer()
  (the framework default now wires enumerateResources(dr::addResource) for the
  onDiscover() path too — one body, no drift). Resources now register from the
  top of every collect cycle and are visible from the cycle they are first seen.
- fix(adapter): enumeration reuses the per-cycle Snapshot (§18), not its own API
  calls. Because enumeration now runs every cycle, issuing the build-16
  discoverer's standalone pulls (dsmInfo/storage/iscsiLunList/shareList/
  per-share NFS probe/upsGet) would be redundant and rate-limit-hostile.
  enumerateResources() now serves entirely from currentSnapshot(): NFS-export
  gating reads the snapshot's nfsRulesByShare (a share is an export iff >0 NFS
  rules — same predicate as build 16, no re-probe); UPS gating reads s.ups
  (non-null iff usb_ups_connect). A snapshot refresh failure propagates
  (unreadable is not invisible — never a silent zero-resource registration).
- chore(adapter): resource keys byte-identical to build 16. rcOf() unchanged
  (name/kind/idKey/idValue order and values identical), so the 25 existing
  devel resources de-duplicate by identifying identifier and do NOT duplicate.
  No metric/property keys, resource kinds, identifiers, relationships, or
  redaction/contract-assert behavior changed.
- fix(framework): pick up framework d59785a (rebuilt vcfcf-adapter-base.jar
  bundled in this pak). Three fixes directly affect Synology: (1)
  RelationshipBuilder ResourceKey arg-order swap fixed — Synology's v2
  relationship edges now persist for the first time (prior v2 builds emitted
  edges whose adapterKind field carried the display name and never compareTo-
  matched a registered resource); (2) ForeignResourceResolver had the same swap
  (line 240) — the build-16 Datastore cross-link's VMWARE/Datastore keys are now
  matchable; (3) ManagedHttpClient.sendWithRoundRobin no longer sets the
  JDK-forbidden Host header that crashed Synology 3036 on multi-homed NAS paths
  since ~06-07 ("restricted header name: Host" -> HttpTimeoutException). No
  adapter source change for (3) — Synology sets no HTTP headers and wires no
  AuthStrategy; the fix is entirely in the bundled framework jar.

## 1.0.0.16 (2026-06-10)

- feat(adapter): restore the v1 informational Datastore cross-link over the v2
  Suite API transport (review WARNING-1, build-15 deferral — now decided).
  Each iSCSI LUN / NFS export that backs a real VMWARE Datastore becomes a
  child of that Datastore (Datastore -> LUN/export), so the Datastore's
  existing HostSystem/VM edges light up the storage dependency graph for free.
  Resolution is by path identity (DataStrorePath), never a MOID:
    - iSCSI LUN: v1's synologyUuidToNaa transform, byte-for-byte — split the
      LUN UUID on '-', rejoin with a 'd' separator, prefix the Type-6 OUI
      "naa.6001405", truncate to a 25-char tail, wrap as "VMFS:|<naa>|".
    - NFS export: "<nas_ip>/<volPath-without-leading-slash>/<share>", one key
      per connected NAS IP (covers round-robin / multi-homed mounts). NAS IPs
      come from the per-cycle Snapshot.networkInterfaces (no new live call).
  Resolution uses ForeignResourceResolver.loadAll("VMWARE","Datastore",
  "DataStrorePath") over a SuiteApiBridge backed by the ambient
  SuiteApiStitcher (new SynologyStitcher helper).
- feat(adapter): v1's exact optional semantics. The stitcher is created in
  ambient mode (no describe.xml credential fields; matches v1's zero-config
  stitch). On a remote collector with no maintenanceuser.properties,
  SuiteApiStitcher.create() throws -> WARN once ("Datastore cross-link skipped
  — Suite API unavailable"), stitcher stays null, and the cycle completes with
  all 25 resources collecting normally and only the cross-link omitted.
  Collection is never failed over the optional cross-link. Stitcher discarded
  in onDiscard (compliance pattern).
- fix(adapter): resolve against real inventory, not phantom Datastores. v1
  minted bare VMWARE/Datastore keys from the computed path even when no VMware
  datastore existed; build 16 emits an edge only when the computed
  DataStrorePath matches a Datastore actually in inventory. Zero datastores
  loaded on a working Suite API connection is legitimate (no VMware datastore
  backed by this NAS) — logged at INFO, never WARN spam. Resolved/matched
  counts logged: "Datastore cross-link: N datastores loaded, M LUN matches,
  K NFS matches".
- chore(adapter): parity preserved — zero changes to the 103 metric/property
  keys, resource kinds, identifiers, or the build-15 redaction/contract-assert
  work. pak-compare vs 1.0.0.15: 0 BLOCKING / 0 WARNING / 0 INFO (the
  cross-link is jar-internal Java behavior; the foreign Datastore is VMWARE's
  resource kind, never declared in this adapter's describe.xml).

## 1.0.0.15 (2026-06-10)

- fix(adapter): redact secrets from thrown/logged messages. SynologyApiClient
  no longer puts the request path/query into exception text — the DSM
  entry.cgi URL carries _sid on every call and account=/passwd= on login, and
  the framework writes exception messages to the on-disk adapter log and the
  Test-connection error. callRaw's HTTP-error message and the logout WARN now
  pass through a redact() helper that masks _sid=, passwd=, and account=
  values; the api/version/method portion is preserved so the failing endpoint
  is still identifiable (rules/no-secrets-on-disk.md; review WARNING-2).
- fix(adapter): contract-assert critical endpoints in Snapshot.build. A
  "success-shaped but empty" {success:true,data:{}} payload from DSM.Info
  (no model) or Core.System.Utilization (no cpu) now throws -> resource ERROR,
  instead of publishing cpu_load_1m=0.0 / system_temp=0.0 sentinels on a GREEN
  instance (unreadable-is-not-readable; review NIT-1). DSM contract: a healthy
  getinfo always carries model; a healthy Utilization.get always carries cpu.
- chore(adapter): build_number 15; byte-identical pak structure vs build 14
  (changes are jar-internal — pak-compare 0/0/0 vs 1.0.0.14).

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
- fix(adapter): drop foreign-resource (Datastore) stitching. v1 landed no data
  on foreign VMWARE resources (golden baseline §3); the informational
  Datastore_parent cross-link on LUN/NFS resources is also dropped here, since
  ForeignResourceResolver/stitchDatastores required a SuiteAPI client that v2
  does not carry. No SuiteApiStitcher added. (Restored in build 16.)
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
