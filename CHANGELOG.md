# Changelog

## 0.0.0.32 (Unreleased)

Dev preview build 32 (`build-sdk` stamps 0.0.0.32); the 1.0.0 release line
is unchanged until a `v1.0.0.32` tag. The factory brief is
knowledge/designs/sdk-adapters/synology-build-32-correctness-and-hygiene.md.
Out of scope here: #11, #16, #32, #35. Issues #33 and #26 were already
closed by build 31 (see the build 31 entries for #13); no change.

- fix(adapter): build 32, issue #17: **a runtime exception from the HTTP layer no longer escapes with the raw request URI in its message.** `callRaw` now catches `RuntimeException` (an `IllegalArgumentException` from URI construction, an `UncheckedIOException`) as well as `IOException` and rethrows a `SynologyTransportException` built only from the endpoint label, the exception's simple class name and the redacted message, with no chained cause, so `passwd=`, `account=` and `_sid=` cannot reach the adapter log or a Test Connection error. The failure class is kept, so a wrapped connect failure is still DOWN. Redaction moved to the SDK-free `SynologyRedaction` (same patterns). Tests: RedactionTest.
- fix(adapter): build 32, issue #27: **the adapter's own log lines no longer name the Suite API account, and Suite API exception messages are redacted before the adapter logs them.** The explicit Suite API path INFO now names the host only. The three WARNs that append a Suite API exception message (stitcher creation failed, Datastore cross-link failed, VMWARE Datastore load failed) pass it through `SynologyRedaction.redact`, which now also masks `password=`, `username=`, `user=`, `token=` and `principal=` parameters (the framework Suite API client ends its 401 and token messages in `principal=<account>`), URL userinfo, JSON `username`, `password`, `token` and `authToken` members (escaped quotes included), and OpsToken, Bearer and Basic credentials. **Still logged: the shared adapter framework's Suite API client, which ships in this pak, writes the account name (`principal=<vrops_username>`) at INFO twice per configure on the explicit path, until the framework is fixed (factory issue pending).** Tests: RedactionTest.suiteApiShapesAreMasked, RedactionTest.frameworkPrincipalAndReviewShapesAreMasked, tests/test_suite_api_log_redaction.py.
- fix(adapter): build 32, issue #23: **each instance, and each Test Connection, uses its own DSM session name.** A collecting instance logs in as `VCFContentFactory-<adapterInstanceId>` (an 8-character random suffix when the platform gives no id) and Test Connection as `VCFContentFactory-test-<random>`, instead of the one shared `VCFContentFactory`, so a Test Connection, an edit or a removal can no longer end the session another instance is using against the same NAS (forcing a 106/107/119 re-login). The logout already sent the client's own `_sid`; it now also names the client's own session. DSM's connection list shows one entry per instance. Tests: SessionNameTest, tests/test_session_name_wiring.py.
- fix(adapter): build 32, issue #18: **DiskStation memory is published in bytes, and network RX/TX is no longer double counted.** DSM reports `memory.total_real`, `avail_real`, `cached`, `total_swap` and `avail_swap` in KB; `Memory|memory_total`, `memory_available`, `memory_cached`, `swap_usage` and `swap_total` now multiply them by 1024, matching their declared unit (bytes). A memory field DSM does not send is skipped, not published as 0. `Network|net_rx_bytes` and `net_tx_bytes` now come from DSM's own `device == "total"` row when it is present; without one, the per-interface rows are summed leaving out `lo` and VLAN interfaces whose parent is listed; without a total row and with a bond listed, the bond's members are unknown and the two keys are not published. **Upgrade note: memory values jump by a factor of 1024 (they were 1024 times too small), and network values drop on NASes whose array had a total row, bonds or VLANs; symptoms, views or super metrics with thresholds on these keys need re-checking. Key names and declared units are unchanged.** Whether this DSM sends a total row is unconfirmed on the lab NAS (devel check). Tests: MetricsTest.
- fix(adapter): build 32, issue #24: **the 1, 5 and 15 minute load averages are no longer labelled as percent.** `CPU|cpu_load_1m`, `cpu_load_5m` and `cpu_load_15m` lose `unit="%"` (now unitless) and their display names change from "CPU Load (1 min)", "CPU Load (5 min)", "CPU Load (15 min)" to "Load Average (1 min)", "Load Average (5 min)", "Load Average (15 min)". Values are published as DSM reports them: DSM appears to scale them by 100 (the devel baseline shows 13 with 0 % CPU on a 4-core NAS), but that is unconfirmed, so they are not divided until devel compares them with the NAS's own load average. **Upgrade note: display names and the unit change; key names and values do not. A symptom or view that treated these as percent needs re-checking.** Tests: tests/test_describe_units.py.
- fix(adapter): build 32, issue #25: **SSD cache metrics on a Volume and the Volume to SSD cache relationship now use the same join.** One helper (`SynologyTopology.serves`, `volumeForCache`, `cacheForVolume`) matches the cache's `mountSpaceId` to the volume's `id`, and only a cache without a `mountSpaceId` falls back to its `path` matching the volume's `vol_path`; the Volume's `Cache|*` keys, the SSD cache display name and the edge all use it. Before, the metrics joined by path and the edge and name by `mountSpaceId`, so a NAS where they disagreed showed cache metrics on a Volume without an SSD cache child, or the reverse. A cache with only a `path` now gets its Volume edge. With both fields agreeing (the lab NAS) nothing changes. (The Datastore cross-link the issue also names has no cache join on build 31 code.) Tests: CacheJoinTest.
- fix(adapter): build 32, issue #34: **Volume free space, disk remaining life and iSCSI LUN latency now carry DSM's values; storage pool capacity is labelled as the allocation it is.** Each key was compared with the DSM 7.3.2 response fields recorded in the factory API maps (`synology-storage.md`, `synology-iscsi.md`). Volume: DSM sends `size.total` and `size.used` and no `size.free`, so `Capacity|free_bytes` read a missing field and was always 0 (`usage_pct` 100 %); free is now `size.free` when sent, else `total - used`, and usage is used / total. Disk: `remain_life` is an object `{trustable, value}`, so `Health|remain_life` was always 0; it now publishes `value`, and not at all for DSM's -1 (HDDs report no wear) or `trustable: false`. iSCSI LUN: DSM's fields are `read_avg_latency` and `write_avg_latency`, so `IO|read_latency` and `write_latency` read missing fields and were always 0; they now read those (the old names are still read if present) and are skipped when absent. Storage pool: DSM's pool `size.used` is the space allocated to volumes, so a fully allocated pool reads 100 % whatever the volumes hold; the values are DSM's and unchanged, and the display names of `Capacity|used_bytes` and `Capacity|usage_pct` change from "Used" and "Usage %" to "Allocated" and "Allocated %". Pool and volume capacity keys DSM did not send, and a usage with a zero or missing total, are skipped instead of published as 0. **Upgrade note: on existing Volumes `free_bytes` and `usage_pct` change from 0 and 100 % to real values; HDDs stop publishing `remain_life` (it was a fabricated 0) and SSDs start publishing their real value; LUN latency becomes non-zero. Pool display names change; keys and units do not.** Unconfirmed until devel: the LUN latency scale (the API map says ms; DSM's Resource Monitor should agree) and that `remain_life.value` matches DSM's Storage Manager. `Health|unc_sectors` = -1 on NVMe (the issue's side note) is unchanged. Tests: MetricsTest, tests/test_describe_units.py.
- fix(adapter): build 32, issue #20: **far fewer NFS rule probes.** No `SYNO.Core.FileServ.NFS.SharePrivilege` probe runs while the NFS service reports `enable_nfs: false` (an unread NFS service response or a missing field still probes, as before); then no share is an export, and an existing NFS export object is ERROR each cycle with a reason naming the NFS service, as build 31 does for an export whose probe failed (review W1), rather than collecting green with a 0 or a stale value for rules that were not read. While NFS is on, a share's answer is reused for up to 15 minutes, then every share is probed again; a share not seen before is probed at once and a share no longer listed is forgotten. A failed probe is not remembered (probed again next cycle, its export still ERROR, review B2), and its WARN is logged at most once an hour per share. Before, every cycle probed every share, NFS on or off, and WARNed every failure every cycle. **Upgrade note: on a NAS with NFS turned off, existing NFS export objects go ERROR (before, they kept collecting from the probe answers DSM still gave), their Volume edge is dropped, a VMWARE Datastore edge stays, and the objects are not removed; turning NFS back on restores them.** Not done: skipping hidden and system shares (see the build 32 report: DSM's `hidden` flag means hidden from the SMB browse list, which does not stop a share being an NFS export, and the recorded fixtures carry no system-share flag). Tests: NfsProbesTest, SnapshotIsolationTest.nfsOffMakesExportsError, tests/test_collect_wiring.py.
- fix(adapter): build 32, issue #28: **the Datastore cross-link pages the Suite API and keeps its index for 30 minutes.** The VMWARE Datastore list is fetched in pages of 1,000 (`page=0,1,...`) until `pageInfo.totalCount` is reached (a short page ends it when no total is sent; at most 100 pages, with a WARN if that cap is hit); before, one `pageSize=10000` request silently dropped every datastore past the first 10,000. The path index is reused for up to 30 minutes instead of being rebuilt every relationship cycle, and reloaded at the next cycle when a LUN or export path that had not missed before finds no Datastore; a path that keeps missing does not force reloads. A failed page, or an empty page before `totalCount` is reached (the list shrank while it was paged, review N3), means no index for the cycle (as before) and a retry the next cycle, never a partial index; a Datastore row returned twice across a page boundary is indexed once. A Datastore created for an already unmatched LUN or export can take up to 30 minutes to get its edge. Tests: DatastoreIndexTest.
- fix(adapter): build 32, issue #22: **a DSM account that requires 2-step verification gets a clear, actionable refusal.** DSM login errors 403 (code required), 404 (verification failed) and 406 (must be set up) now read, for example, "Synology login failed: error code 403 (2-step verification code required: this adapter does not support 2-step verification; use a dedicated DSM account without it (see docs/installing.md). Not retried until the back-off ends or the instance is edited)". They go through the existing login refusal path: no retry within the cycle, then the 15, 30, 60 minute back-off. docs/installing.md and README now ask for a dedicated administrators-group account without 2-step verification, and docs/installing.md says how to harden it (leave it out of enforced 2-step verification, deny unused applications, DSM firewall allowing TCP 5001 only from the collector or Cloud Proxy). Device-token (`enable_device_token`) support is not added. Tests: FailureMappingTest.twoStepRefusalIsActionableAndBacksOff.
- feat(adapter): build 32, issue #31: **DSM API versions come from `SYNO.API.Info`.** At its first login each API client (one per instance configuration, and Test Connection) calls `SYNO.API.Info query` on `/webapi/query.cgi` (no session needed) and calls every API with `min(maxVersion DSM offers, the version the adapter was written for)` on the CGI path DSM names (a path that is not a plain `*.cgi` name falls back to `entry.cgi`). The hardcoded versions stay as the desired values, so on DSM 7.2 and later nothing changes; one WARN per client names any API used below its desired version and says its values are unverified, since an older payload may lack fields the adapter reads (review W4); an API that DSM offers only above the desired version (`minVersion`) counts as not offered (review N2). If DSM does not list a required API (the login or one of the four required reads) one WARN names it; every unlisted API is still called the build 31 way, so the NAS's own answer (and the DSM error 102 "not installed" logic for iSCSI) still decides. If `SYNO.API.Info` cannot be read, the built-in versions are used with one WARN and the login goes ahead; that client does not ask again (review N6), and an instance edit builds a new client that does. An unreachable NAS on that call is the exception: it fails the login attempt as DOWN at once rather than waiting out a second set of timeouts. docs/installing.md states the supported DSM versions (7.2 or later; tested on 7.3.2; 6.x and 7.0 to 7.1 untested). Tests: DsmApisTest, tests/test_api_info_wiring.py.
- docs(adapter): build 32, issue #29: **docs match the code.** docs/installing.md now lists the optional `vrops_url`, `vrops_username` and `vrops_password` credential fields and says the explicit Suite API path (remote collector or Cloud Proxy) needs outbound HTTPS (TCP 443) to the primary or analytics node, where before it said the cross-link needed no extra network configuration. docs/overview.md gives the real key count (118 metric and property keys across the nine kinds, 98 distinct names, instead of "roughly 130"). The SSD cache `Hardware|total_capacity` property divides by 1024^3 and now says `GiB` (it said `GB`). **Upgrade note: the `total_capacity` property value text changes from, for example, "1863.0 GB" to "1863.0 GiB"; the number is unchanged.** Already true on build 31, no change: the Allow Insecure SSL default (`false` in describe.xml, README, REFERENCE and docs/installing.md) and the account scope text (docs/installing.md says the four required endpoints, the administrators group, and that every other endpoint is optional and skipped when refused). Kept: README's Disk "drive family" property, which exists (`disk_code`, display name "Drive Family", nameKey 261). Tests: tests/test_describe_units.py.
- docs(adapter): build 32, issue #37: README and docs/installing.md give the VCF Operations 9.x menu paths with the 8.x path beside them, as the UniFi README does: upload under **Administration > Integrations > Repository > Add** (8.x: Data Sources > Integrations > Repository), account under **Administration > Integrations > Accounts > Add Account** (8.x: Data Sources > Integrations > Accounts). Before, README Configure and both install steps used the pre-9.x Administration > Solutions path. README's object table and docs/overview.md now use the build 32 names (pool allocated, load averages, review N4). Not yet checked against a 9.x UI.

## 0.0.0.31 (Unreleased)

Dev preview build 31 (`build-sdk` stamps 0.0.0.31); the 1.0.0 release line
is unchanged until a `v1.0.0.31` tag. The factory brief is
knowledge/designs/sdk-adapters/synology-build-31-availability-and-tls.md.
Issue #11 (multi-NAS identity collision) is deferred to its own build.

- fix(adapter): build 31, issue #15: **Behavior change: Allow Insecure SSL defaults to `false`, so a new instance validates the NAS certificate.** describe.xml ships `default="false"`; README, REFERENCE and docs/installing.md now all say false. The parse is now strict: only `true` (any letter case) turns validation off, anything else validates (build 30 skipped validation for every value except `false`). configure logs one WARN while insecure mode is on. **Upgrade note: existing instances keep their stored value.** An instance created with the old default stores `true` and keeps working until it is edited to `false`. An instance whose stored value is blank or any text other than `true` now validates the certificate, and against DSM's factory self-signed certificate it fails with a certificate message until the certificate is accepted (below), Allow Insecure SSL is set to `true` (lab use only), or the certificate is imported. Validate Connection offers the NAS certificate for review (the `certificateCheckUrls` hook, as compliance build 93): a NAS with an untrusted, for example factory self-signed, certificate is accepted once in VCF Operations' Review and Accept Certificate dialog, the certificate is stored on the instance and validation stays on. No dialog is offered while Allow Insecure SSL is `true`. README and docs/installing.md say to enter the NAS by a name its certificate carries, since by an IP address the certificate does not list the review dialog is unproven and Allow Insecure SSL may be the only path. Tests: AllowInsecureTest, tests/test_allow_insecure_default.py.
- fix(adapter): build 31, issue #12: **a failed login is cached, so a wrong password costs one login, not one per resource, and DSM auth refusals back off.** Before, every resource of the cycle rebuilt a failed snapshot and logged in again, enough to trip DSM Auto Block against the collector's IP. Now the first caller of the cycle (usually the collect-path discovery step) gets the failure and the rest of the cycle fails from it without contacting the NAS. The adapter log gets one `Synology snapshot build failed` WARN per attempted login, plus the framework's own line for the discovery step and for each resource that fails from the cached result. Each of those framework lines is one line, the discovery step's included, on the cycle that tried the login (the first cycle and every back-off expiry) as on the cycles served from the cache: the caller whose build failed gets the same stackless, classified exception the cache serves later, naming the original's class and message and carrying its classification (login refusal code, unreachable) as fields, with no cause chain or stack trace (devel_9_1 follow-up: the attempt cycles' discovery WARN carried an 18-frame stack). The original, stack included, is logged at DEBUG with the adapter's WARN line, through a logger resolved once per configure and stored with the cache (the framework's componentLogger resets a logger to INFO on every call, factory issue #224, so a per-failure lookup could never write it); a fault in that logger is swallowed and never reaches the collect path. After a DSM login refusal (error 400, 401, 402, 403, 404, 406, 407, 408, 409 or 410 in the body of the 200 response) no login is tried for 15 minutes, then 30, then every 60 minutes while the refusals continue, unless the instance is edited (an edit starts clean and may log in at once); after any other failure the next cycle retries. Test Connection does not use the cache. Tests: SnapshotCacheTest, FailureMappingTest, tests/test_snapshot_logger_once.py.
- fix(adapter): build 31, issue #13: **an unreachable NAS costs one attempt per cycle and is DOWN, not ERROR.** The snapshot is built once per collection cycle and stamped when its build finishes (before, a build slower than 60 s was rebuilt by every resource); a 15-minute age is only a safety net. Connection refused, connect or request timeouts, an unknown host and no route to host map to DOWN wherever they sit in the cause chain (before, the API client rethrew them as plain IOExceptions, so the bare-ConnectException check never matched). Stop Collecting ends a snapshot build between DSM calls (the first check runs before the login), and an optional read, the UPS read or a per-share NFS probe that cannot connect or times out ends the build instead of letting every remaining call wait out its own three 30 s attempts (a NAS that answers the required reads and then hangs costs one timeout per cycle, not one per share). Connect and read timeouts stay one 30 s value: the framework HttpClientBuilder exposes only one. Tests: SnapshotTimingTest, FailureMappingTest, SnapshotReadsTest.loginRefusalAndConnectFailureStillFailTheBuild, NfsProbesTest.connectFailureAndLoginRefusalEndTheBuild.
- fix(adapter): build 31, issue #14: **one optional DSM endpoint no longer hides the whole NAS.** Only DSM info, system info, utilization and storage load_info are required. FanSpeed, NFS service, iSCSI LUN and target, network interface, current connection and share list failures each log one adapter WARN per instance configuration (an INFO when readable again) and skip only their dependent keys, objects and relationships, never a placeholder value; existing LUN and NFS export objects go ERROR each cycle while their list is unread (the framework logs its own line for each), and so does an existing NFS export whose per-share NFS rule probe failed (before, it published `rule_count = 0` and an empty `allowed_clients` for rules that were not read, and lost its Volume edge for the cycle), and Volume relationships are left unchanged rather than dropped: every Volume's while the LUN or share list is unread, and only the Volume a failed-probe share sits on (its `vol_path`) while other Volumes refresh (review NIT-R3). DSM error 102 (the API does not exist) on the iSCSI LUN or target list, a NAS without iSCSI, is read as an empty list with one INFO once two consecutive cycles answer it, so Volume relationships keep refreshing there; a single 102 (SAN Manager still starting after a NAS reboot) is not read, so existing LUNs go ERROR for that cycle and keep their Volume edges instead of reading as "no LUNs", and any other answer resets the count (review NIT-R1); a permanent refusal (a missing privilege) still leaves them unrefreshed, as docs/installing.md states. docs/installing.md documents the minimum DSM privileges (an administrators-group account without 2-step verification) and what each optional endpoint carries. With every endpoint readable the keys and relationships are unchanged. Tests: SnapshotReadsTest, NfsProbesTest, SnapshotIsolationTest (the adapter-side "which list does this object need" and "are Volume children known" decisions, in an SDK-free helper the adapter delegates to).
- fix(adapter): build 31, issue #21: editing the instance logs the old DSM session out and discards the old Suite API stitcher (the framework already discards the old HTTP client), best effort, through the same teardown as removing the instance. Before, every edit left a DSM session open until DSM expired it. The old API client is closed before its logout and never logs in again, so a cycle still running on it across the edit cannot re-login after the logout (a session nobody logs out, or a refused login with the old password counting toward DSM Auto Block); a login in flight at close is logged straight out, and the login's closed check and session store run under the same lock as close, so a login finishing during close can never leave a session behind (review NIT-R2). The logout is one attempt with a 5 s timeout over a dedicated client, so a host change away from a dead NAS does not hold configure for three 30 s attempts; closing the API client discards that dedicated client after the logout attempt, with or without a session and even when the logout fails, so an edit or a removal no longer leaks one client (Codex P2); a login in flight at close keeps that client until it has logged its new session out, and the last such login discards it, exactly once. Tests: TeardownTest, DsmSessionTest, SnapshotIsolationTest.reconfigureReleasesOnlyWhatWasReplaced, tests/test_logout_client_discard.py.
- fix(adapter): build 31, issue #19: **one oddly shaped object no longer wipes every relationship.** The relationship joins are null-safe and per object: a pool without `pool_path`, a LUN without `location` or a volume without an identifier loses only its own edge (one INFO line names it) instead of throwing and publishing no relationships for the cycle. Same edges as build 30 for well-formed data. Tests: TopologyTest.
- test(adapter): build 31 adds `ci/run_java_tests.sh` (plain-main Java tests and stdlib Python checks, no SDK jar needed). CI now runs them on every push and pull request (`.github/workflows/tests.yml`, calling the SDK template's reusable `pak-tests.yml` on JDK 17 and 25), the pull-request half of issue #32 item CI7; the tag workflow does not run them yet.

## 1.0.0.30 (2026-08-18)

- fix: Allow Insecure SSL now ignores certificate hostname mismatches (issue #5). No adapter source change: rebuilt against sdk-buildkit v1.0.10, which fixes vcf-content-factory#82 (`insecureSslContext()` reimplemented as `X509ExtendedTrustManager` so JSSE no longer re-arms the endpoint identity check). Build 29 was pulled: it was tagged without the `build_number` bump, so its pak was stamped 1.0.0.28 and version-indistinguishable from the prior release.

## 0.0.0.28 (2026-07-06)

- fix(synology): build 28 — reviewer NIT from build 27 (`context/reviews/synology-build-27.md`): backtick the `<nas_ip>/<vol_path>/<share>` placeholder in the NFS `cross_mp_edges` description so GitHub-rendered markdown doesn't strip the angle-bracket tokens down to "computed  //  path"; matches how `docs/overview.md:63` already renders it. Docs-only.

## 0.0.0.27 (2026-07-06)

- feat(synology): build 27 — docs-only. Adds the `cross_mp_edges`
  adapter.yaml stanza (framework support: local working tree / PR #33,
  gate-APPROVEd `context/reviews/framework/managementpacks-docs-gen-2026-07-06.md`)
  documenting the certified `VMWARE Datastore → {SynologyIscsiLun,
  SynologyNfsExport}` `parentForeign` cross-link built by
  `SynologyStitcher`/`emitDatastoreCrossLink` (see build 16's `parentForeign`
  migration and DEF-003/DEF-006). No adapter Java changed; `docs/README.md`
  and `docs/inventory-tree.md` regenerated by `build-sdk` now carry a
  "Cross-MP Relationships" section so the generated docset stops silently
  omitting this runtime-only Suite API edge.

## 0.0.0.26 (2026-07-02)

- feat(synology): build 26 — **the convergence build** (design-of-record:
  `designs/stitcher-identity-v3-adapter-credentials.md`; `context/defects.md`
  DEF-006). Combines a framework re-pull with two adapter-source changes.
  This is the build that goes to the prod CP fixture to close DEF-006.
  1. **Inherited (framework, no adapter action — rides in via the rebuilt
     `adapter_framework/` jar):** ambient identity v3 — injected
     per-instance credential first, then `automationuser.properties`, then
     `maintenanceuser.properties` — with the `file=instance` selection log
     and the `instance-credential not used reason=…` breadcrumb.
     `AmbientCredential`/`SuiteApiStitchClient` live in
     `vcfops_managementpacks/adapter_framework/`, not in this adapter's
     `src/`; no synology source references them directly. Reviews:
     `context/reviews/framework/ambient-credential-v3-instance-first.md` +
     `-closeout.md`.
  2. **NFS NIC-selection fix (build-25 finding):** the only code path in
     this adapter that ever picked a single NAS IP
     (`SynologyStitcher.connectedNasIps(...).get(0)`) was the build-25
     experimental `relationships|Datastore_parent` property emission in
     `collectNfsExport` — the first connected interface (`.51`) is not
     necessarily the one ESXi mounted via (`.52`), so that constructed
     `<ip>/<export>` candidate could never match the real datastore
     identity. That code is removed in this build (see #3), which
     eliminates the bug at its only source. The **runtime credentialed
     cross-link** (`SynologyAdapter#emitDatastoreCrossLink` →
     `SynologyStitcher#matchByPath`, build 16/18) was never affected: it
     already iterates every entry from `connectedNasIps(...)` and tries
     `nfsDataStorePath(ip, volPath, name)` for each one, matching against
     real VMWARE Datastore inventory
     (`content/sdk-adapters/synology/src/com/vcfcf/adapters/synology/
     SynologyAdapter.java`, `emitDatastoreCrossLink`, NFS loop). The
     LUN/VMFS path is unaffected either way — `lunDataStorePath` keys off
     the LUN's own UUID→NAA transform, no NIC dependency.
  3. **Removed the build-25 experiment (proven inert):** deleted the
     `relationships|Datastore_parent` property emission from
     `collectIscsiLun`/`collectNfsExport`
     (`SynologyAdapter.java`), the `relationships` `ResourceGroup` +
     `Datastore_parent` `ResourceAttribute` blocks from `describe.xml`
     (both `SynologyIscsiLun` and `SynologyNfsExport`), and nameKeys
     285-288 from `resources/resources.properties`. Platform evidence: no
     property→edge code path exists
     (`context/investigations/platform-edge-engine-2026-07-02.md`). The
     pre-existing (build-18) `TraversalSpecKind` cross-MP navigation hops
     for `VMWARE::Datastore::~child` are untouched — harmless navigation
     metadata, unrelated to the deleted property mechanism.
  - Devel build feeding the prod CP fixture — no `v*` release tag yet.
    Per RULE-014 (`rules/pak-version-lines.md`) this hand build is
    expected to stamp `0.0.0.26`.

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
