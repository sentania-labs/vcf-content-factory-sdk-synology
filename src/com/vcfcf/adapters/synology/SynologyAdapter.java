package com.vcfcf.adapters.synology;

import com.vcfcf.adapter.VcfCfAdapter;
import com.vcfcf.adapter.http.HttpClientBuilder;
import com.vcfcf.adapter.http.ManagedHttpClient;
import com.vcfcf.adapter.json.SimpleJson;
import com.vcfcf.adapter.retry.RetryPolicy;
import com.vcfcf.adapter.spi.ResourceSink;
import com.vcfcf.adapter.spi.VcfCfCollector;
import com.vcfcf.adapter.spi.VcfCfTester;
import com.vcfcf.adapter.stitch.RelationshipBuilder;
import com.vcfcf.adapter.stitch.SuiteApiStitcher;

import com.integrien.alive.common.adapter3.AdapterBase;
import com.integrien.alive.common.adapter3.Logger;
import com.integrien.alive.common.adapter3.MetricData;
import com.integrien.alive.common.adapter3.MetricKey;
import com.integrien.alive.common.adapter3.Relationships;
import com.integrien.alive.common.adapter3.ResourceKey;
import com.integrien.alive.common.adapter3.ResourceStatus;
import com.integrien.alive.common.adapter3.config.ResourceConfig;
import com.integrien.alive.common.adapter3.config.ResourceIdentifierConfig;
import com.integrien.alive.common.util.CommonConstants.ResourceStatusEnum;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Synology DiskStation adapter — framework v2 (build 14).
 *
 * <p><b>v1 → v2 SPI port.</b> Re-homed from aria-ops-core
 * ({@code UnlicensedAdapter} + {@code com.vmware.tvs.*}) onto
 * {@link VcfCfAdapter} (which extends {@code AdapterBase} directly) and the
 * {@code com.vcfcf.adapter.spi} roles: {@link VcfCfTester}, collect-path
 * discovery ({@link #enumerateResources}), {@link VcfCfCollector}. No
 * {@code com.vmware.tvs.*},
 * no {@code Resource}/{@code ResourceCollection}, no JAX-WS. Transport is the
 * framework {@link ManagedHttpClient} over the existing DSM Web API client
 * ({@link SynologyApiClient}); auth/session logic carries over functionally.
 *
 * <p><b>No metric stitching; informational Datastore cross-link restored
 * (build 16).</b> Synology pushes no metrics/properties onto foreign VMWARE
 * resources (golden baseline §3 confirms: zero Synology-namespaced data on any
 * HostSystem or Datastore). It does, however, emit v1's <em>informational</em>
 * Datastore cross-link: each iSCSI LUN / NFS Export that backs a real VMWARE
 * Datastore becomes a child of that Datastore (Datastore → LUN/export), so the
 * Datastore's existing HostSystem/VM edges light up the storage dependency
 * graph for free. The Datastore is resolved by its path identity
 * ({@code DataStrorePath}; NAA transform for LUNs, {@code ip/serverPath} for
 * NFS — never a MOID) via {@code ForeignResourceResolver.loadAll("VMWARE",
 * "Datastore","DataStrorePath")} over the ambient {@link SuiteApiStitcher}
 * (see {@link SynologyStitcher}). This carries v1's exact optional semantics:
 * v1's {@code stitchDatastores} was already gated on Suite API availability, so
 * a remote collector with no {@code maintenanceuser.properties} simply WARNs
 * once and proceeds — all 25 resources still collect; collection is never
 * failed over the optional cross-link. Unlike v1 it resolves against real
 * inventory rather than minting phantom Datastore keys: zero matches on a
 * working connection is legitimate (no VMware datastore backed by this NAS).
 * Parity bar is the 25 own resources / 103 metric-property keys in the golden
 * baseline (unchanged by this build).
 *
 * <p><b>Per-resource collect reshape.</b> v2 calls {@code collect(rc)} once per
 * discovered resource (not once for the whole topology like v1). To preserve v1's
 * single-pull semantics and value parity, a per-cycle {@link Snapshot} caches the
 * Synology API responses; each {@code collect(rc)} dispatches on the resource kind
 * and serves from the snapshot. Relationships are emitted as the full topology on
 * the World resource's {@code collectRelationships} call (v1 set
 * {@code shouldForceUpdateRelationships=true} — full-set every cycle).
 *
 * <p><b>Unreadable is not invisible.</b> A REST/session failure on the per-cycle
 * snapshot refresh throws out of {@code collect()} so the framework marks the
 * resource ERROR/DOWN (never a silent empty result). Per-endpoint sub-failures
 * are logged WARN/INFO: UPS absent skips the UPS; a failed NFS rule probe makes
 * an existing export on that share ERROR (build 31, review B2). No sentinel
 * values are pushed for state that could not be read.
 */
public final class SynologyAdapter extends VcfCfAdapter<SynologyConfig> {

	private static final String ADAPTER_KIND = "synology_diskstation";

	/**
	 * Build 31 (issue #12): the DSM API client and the snapshot cache of one
	 * configuration, published together by one volatile write in
	 * {@link #configureAdapter}. An instance edit publishes a new holder, so
	 * a cached failure (and its login back-off) never outlives the settings
	 * it failed with, and a call never pairs one configuration's client with
	 * another's cache. Null until the first configure.
	 */
	private volatile Configured configured;

	/**
	 * Ambient Suite API transport for the optional Datastore cross-link, and the
	 * Synology-specific resolver over it. Both null when the Suite API is
	 * unavailable (remote collector with no {@code maintenanceuser.properties}) —
	 * the cross-link is then skipped for the cycle and collection proceeds.
	 */
	private volatile SuiteApiStitcher suiteStitcher;
	private volatile SynologyStitcher stitcher;

	/**
	 * Per-cycle snapshot of the Synology API responses, shared across all
	 * per-resource {@code collect()} calls within one collection cycle, lives in
	 * the {@link SnapshotCache} of the current {@link Configured} holder.
	 *
	 * <p>Build 31 (issue #13): one build per collection cycle. The cycle, not
	 * the clock, decides reuse; this age (counted from the end of the build)
	 * is only a safety net that rebuilds a snapshot in a cycle that has run
	 * this long. Before build 31 the snapshot was rebuilt after 60 s counted
	 * from the start of its build, so a build slower than 60 s was rebuilt by
	 * every resource.
	 */
	private static final long MAX_SNAPSHOT_AGE_MS = 15L * 60_000L;

	/**
	 * Build 31 (issue #12): after a failed snapshot build that is not an
	 * authentication refusal, no new build before this has passed (and never
	 * in the same cycle). Shorter than the 5-minute default collection
	 * interval, so an unreachable NAS is tried once per cycle.
	 */
	private static final long FAILURE_RETRY_BACKOFF_MS = 60_000L;

	/**
	 * Build 31 (issue #12): after the first authentication-class DSM login
	 * refusal (error 400, 401, 402, 403, 404, 406, 407, 408, 409 or 410), no
	 * new build (and so no login) before this has passed, unless the
	 * instance is edited. Doubled for each further consecutive refusal, up
	 * to {@link #MAX_AUTH_FAILURE_BACKOFF_MS}: at the default 5-minute
	 * interval a wrong password costs one failed login after 15, 30, then
	 * every 60 minutes, instead of one per resource per cycle, far below
	 * DSM Auto Block's default threshold.
	 */
	private static final long AUTH_FAILURE_BACKOFF_MS = 15L * 60_000L;

	/** Build 31 (issue #12): the cap on the doubled authentication back-off. */
	private static final long MAX_AUTH_FAILURE_BACKOFF_MS = 60L * 60_000L;

	/**
	 * Build 31 (review W5): the connect and read timeout of the DSM logout
	 * sent when a configuration is released (instance edited or removed),
	 * in one attempt. A logout that times out leaves an idle session for
	 * DSM to expire; before, an unreachable old NAS held configure (or
	 * discard) for three 30 s attempts.
	 */
	private static final Duration LOGOUT_TIMEOUT = Duration.ofSeconds(5);

	/**
	 * One configuration's DSM API client, snapshot cache, the snapshot
	 * cache's logger and optional-endpoint WARN state (build 31, issue #14)
	 * (immutable). The logger is resolved once in configureAdapter and
	 * stored here: componentLogger resets the logger to INFO on every call
	 * (factory issue #224), so a per-failure lookup would make the cache's
	 * DEBUG line unreachable even after an operator raised the level.
	 */
	private static final class Configured {
		final SynologyApiClient api;
		final Logger snapshotLog;
		final SnapshotCache<Snapshot> snapshots;
		final SnapshotReads reads;

		Configured(SynologyApiClient api, Logger snapshotLog,
				SnapshotCache<Snapshot> snapshots, SnapshotReads reads) {
			this.api = api;
			this.snapshotLog = snapshotLog;
			this.snapshots = snapshots;
			this.reads = reads;
		}
	}

	/**
	 * Build 32 (issue #23): the platform's adapter instance id, for this
	 * instance's DSM session name; {@code null} on a bare instance.
	 */
	private final Integer instanceId;

	public SynologyAdapter() {
		super(ADAPTER_KIND);
		this.instanceId = null;
	}

	public SynologyAdapter(String adapterDir, Integer adapterInstanceId) {
		super(ADAPTER_KIND, adapterDir, adapterInstanceId);
		this.instanceId = adapterInstanceId;
	}

	// -----------------------------------------------------------------------
	// onDescribe — framework default (resolves describe.xml from the
	// constructor-stored ADAPTER_KIND; safe under controller-side bare
	// instantiation where getAdapterKind() is null). Do NOT implement.
	// See lessons/controller-describe-bare-instantiation.md.
	// -----------------------------------------------------------------------

	// -----------------------------------------------------------------------
	// configureAdapter (replaces v1 configure)
	// -----------------------------------------------------------------------

	@Override
	protected void configureAdapter(ResourceStatus status,
			ResourceConfig resourceConfig) {
		// Build 31 (issue #21): the configuration this one replaces. Released
		// (DSM client closed and logged out, Suite API stitcher discarded)
		// once the new one is published, by the same teardown onDiscard uses.
		// Before build 31 an edit left the old DSM session open until DSM
		// expired it, and the old Suite API session unreleased. The old HTTP
		// client is not ours to release here: the framework's onConfigure
		// discards it and clears this.httpClient before calling
		// configureAdapter (review NIT-1). The old DSM logout does not use
		// it; it goes over the old API client's own logout client.
		final Configured oldConfigured = this.configured;
		final SuiteApiStitcher oldSuiteStitcher = this.suiteStitcher;

		SynologyConfig cfg = buildConfig(resourceConfig);
		this.config = cfg;
		this.httpClient = buildHttpClient(cfg);
		// Resolved once per configure, never per failure (see Configured).
		Logger snapshotLog = componentLogger(SnapshotCache.class);
		this.configured = new Configured(
				// Build 31 (review W5): the logout goes over its own
				// single-attempt client with a short timeout (same TLS
				// settings), so releasing this configuration later is bounded.
				new SynologyApiClient(httpClient,
						buildHttpClient(cfg, 1, LOGOUT_TIMEOUT),
						cfg.username, cfg.password,
						// Build 32 (issue #23): one DSM session name per instance.
						SynologyConfig.sessionName(instanceId, false,
								SynologyConfig.randomToken()),
						componentLogger(SynologyApiClient.class)),
				snapshotLog,
				new SnapshotCache<>(System::currentTimeMillis,
						MAX_SNAPSHOT_AGE_MS, FAILURE_RETRY_BACKOFF_MS,
						AUTH_FAILURE_BACKOFF_MS, MAX_AUTH_FAILURE_BACKOFF_MS,
						snapshotLog::debug),
				new SnapshotReads());

		// Optional Datastore cross-link transport (build 16; explicit-creds
		// branch build 21). Two paths, selected by config:
		//   - AMBIENT (default): create() reads maintenanceuser.properties and
		//     targets https://localhost/suite-api. Works on a primary/analytics
		//     node; on a remote collector that file is absent (or localhost
		//     serves no global VMWARE inventory → 403). This is the v1 path and
		//     is byte-unchanged when the explicit fields are blank.
		//   - EXPLICIT (remote collector / Cloud Proxy): when vrops_username AND
		//     vrops_password are configured, createExplicit() targets the
		//     operator-supplied PRIMARY/analytics FQDN (parsed from vrops_url)
		//     with those credentials — the only path that serves the global
		//     VMWARE inventory off-primary (spec 20 §4 / suite-api TLS-auth
		//     design §0.1 Q2). Pointing explicit creds at localhost-on-collector
		//     still reads nothing, so the operator must supply the primary FQDN.
		// Either factory throwing leaves the stitcher null and degrades exactly
		// as v1 did: WARN once, all 25 resources still collect, cross-link
		// skipped for the cycle. The password is never logged.
		final boolean explicitSuiteApi = cfg.hasExplicitSuiteApi();
		final String suiteHost = explicitSuiteApi ? cfg.suiteApiHost() : null;
		try {
			if (explicitSuiteApi) {
				// Loud-fail the spec §4 / ledger-#13 sharp edge: explicit creds
				// set but a blank, malformed, or localhost URL all collapse to a
				// loopback host, which serves no global VMWARE inventory on a
				// remote collector (403). Still take the explicit path as
				// configured — but make the misconfiguration unambiguous rather
				// than letting a localhost-aimed explicit stitch look configured
				// while silently reading nothing.
				if (cfg.suiteApiHostIsLoopback()) {
					logWarn("Datastore cross-link: explicit Suite API credentials "
							+ "are set but the URL is missing, malformed, or "
							+ "resolves to localhost (" + suiteHost + "); on a "
							+ "remote collector this cannot reach the cluster's "
							+ "global VMWARE inventory and will 403 — set the Suite "
							+ "API URL to the PRIMARY/analytics FQDN. Proceeding on "
							+ "the explicit path as configured.");
				}
				this.suiteStitcher = SuiteApiStitcher.createExplicit(this,
						componentLogger(SuiteApiStitcher.class),
						suiteHost, cfg.suiteApiUser, cfg.suiteApiPassword);
				// Build 32 (issue #27): host only; the Suite API account is
				// not written to the adapter log.
				logInfo("Datastore cross-link: explicit Suite API path -> "
						+ suiteHost);
			} else {
				this.suiteStitcher = SuiteApiStitcher.create(this,
						componentLogger(SuiteApiStitcher.class));
				logInfo("Datastore cross-link: ambient Suite API path "
						+ "(localhost / maintenance user)");
			}
			this.stitcher = new SynologyStitcher(this.suiteStitcher,
					componentLogger(SynologyStitcher.class));
		} catch (RuntimeException e) {
			this.suiteStitcher = null;
			this.stitcher = null;
			logWarn("Datastore cross-link skipped — Suite API unavailable "
					+ (explicitSuiteApi
							? "(explicit creds to " + suiteHost
									+ " could not be resolved): "
							: "(remote collector without maintenanceuser.properties?): ")
					// Build 32 (issue #27): redacted before it is logged.
					+ SynologyRedaction.redact(e.getMessage()));
		}

		logInfo("SynologyAdapter configured: host=" + cfg.host
				+ " port=" + cfg.port + " allowInsecure=" + cfg.allowInsecure
				+ " datastoreCrossLink=" + (stitcher != null));
		// Build 31 (issue #15): one WARN per configure while insecure mode is on.
		String insecure = cfg.insecureWarning();
		if (insecure != null) {
			logWarn(insecure);
		}

		// Build 31 (issue #21): release the replaced configuration. The new
		// API client has not logged in yet (its first snapshot build does),
		// so the old session's logout cannot end the new one.
		releaseConfiguration("instance reconfigured", oldConfigured,
				SnapshotIsolation.replaced(oldSuiteStitcher, this.suiteStitcher));
	}

	/**
	 * Build 31 (issue #21): the teardown shared by {@link #configureAdapter}
	 * (for the configuration it replaces) and {@link #onDiscard}. Closes the
	 * old DSM API client first (review W5: marked closed so a cycle still
	 * running on it never logs in again, then its session is logged out in
	 * one attempt bounded by {@link #LOGOUT_TIMEOUT}), then discards the
	 * Suite API stitcher. Best effort: every step runs, a failure is one
	 * WARN, nothing is thrown. Null arguments are skipped. The main HTTP
	 * client is the framework's to discard (review NIT-1): its onConfigure
	 * does it before configureAdapter, and super.onDiscard() on discard. The
	 * dedicated logout client is discarded by the API client's close
	 * (Codex P2).
	 */
	private void releaseConfiguration(String why, Configured c,
			SuiteApiStitcher st) {
		Teardown.run(why, this::logWarn,
				Teardown.step("DSM logout", c == null ? null : c.api::close),
				Teardown.step("Suite API stitcher discard",
						st == null ? null : st::discard));
	}

	// -----------------------------------------------------------------------
	// certificateCheckUrls (framework hook; build 31, review W1)
	// -----------------------------------------------------------------------

	/**
	 * Build 31 (review W1): the NAS endpoint VCF Operations reviews on
	 * Validate Connection, so an untrusted certificate (DSM's factory
	 * self-signed one, for example) brings up the Review and Accept
	 * Certificate dialog instead of failing Test Connection. The accepted
	 * certificate is stored on the instance and trusted by
	 * {@code platformSsl(this)} on every cycle, with validation still on.
	 * Derived only from the passed, possibly unsaved config, never from
	 * instance state; empty when Allow Insecure SSL is on (read with this
	 * pak's own {@link SynologyConfig#parseAllowInsecure}, the parse the
	 * client uses, not the framework's trimmed one). See
	 * {@link SynologyConfig#certificateCheckUrls}. {@code @Override} on
	 * purpose, as compliance build 93: a buildkit without the hook must fail
	 * to compile rather than build a pak that never offers the dialog.
	 */
	@Override
	protected List<String> certificateCheckUrls(ResourceConfig rc) {
		return SynologyConfig.certificateCheckUrls(getIdentifier(rc, "host"),
				getIdentifier(rc, "port"), getIdentifier(rc, "allowInsecure"));
	}

	private SynologyConfig buildConfig(ResourceConfig rc) {
		String host = getIdentifier(rc, "host");
		String port = getIdentifier(rc, "port");
		String allowInsecure = getIdentifier(rc, "allowInsecure");
		String username = getCredentialField(rc, "username");
		String password = getCredentialField(rc, "password");
		// Optional explicit Suite API credentials (remote collector / Cloud Proxy).
		// Absent on a primary node → null → blank → ambient path (byte-unchanged).
		String suiteApiUrl = getCredentialField(rc, "vrops_url");
		String suiteApiUser = getCredentialField(rc, "vrops_username");
		String suiteApiPassword = getCredentialField(rc, "vrops_password");
		return new SynologyConfig(host, port, username, password, allowInsecure,
				suiteApiUrl, suiteApiUser, suiteApiPassword);
	}

	/**
	 * Build 31 (issue #13): the timeout stays one 30 s value for connect and
	 * read because the framework {@link HttpClientBuilder} exposes only
	 * {@code timeout(Duration)}, applied to both. What bounds a cycle against
	 * an unreachable NAS is the {@link SnapshotCache}: at most one snapshot
	 * build per cycle, a connect failure ends that build, and the other
	 * resources fail from the cached result.
	 */
	private ManagedHttpClient buildHttpClient(SynologyConfig cfg) {
		return buildHttpClient(cfg, 3, Duration.ofSeconds(30));
	}

	/**
	 * Build 31 (review W5): the same client with a given attempt count and
	 * timeout (the bounded logout client uses one attempt and
	 * {@link #LOGOUT_TIMEOUT}).
	 */
	private ManagedHttpClient buildHttpClient(SynologyConfig cfg,
			int attempts, Duration timeout) {
		HttpClientBuilder b = HttpClientBuilder.builder()
				.baseUrl(cfg.baseUrl())
				.retryPolicy(RetryPolicy.builder()
						.maxAttempts(attempts)
						.baseDelayMs(1000)
						.build())
				.timeout(timeout);
		if (cfg.allowInsecure) {
			// Lab opt-out: Synology DSM ships a self-signed cert by default.
			// Build 31 (issue #15): only an explicit "true" gets here.
			b.allowInsecure(true);
		} else {
			b.platformSsl(this);
		}
		return b.build();
	}

	// -----------------------------------------------------------------------
	// getTester — self-contained (controller calls it on a BARE instance:
	// configureAdapter has NOT run, so this.configured / this.config are null). Derive
	// everything from the ResourceConfig on the TestParam. Mirror compliance
	// build-46 tester pattern.
	// -----------------------------------------------------------------------

	@Override
	protected VcfCfTester<SynologyConfig> getTester() {
		return (cfg, http, param) -> {
			ResourceConfig rc = testResourceConfig(param);
			if (rc == null) {
				throw new Exception("Test-connection: no adapter-instance "
						+ "ResourceConfig available on TestParam — cannot read "
						+ "Synology host/credentials to test");
			}
			SynologyConfig testCfg = buildConfig(rc);
			ManagedHttpClient testHttp = buildHttpClient(testCfg);
			try {
				// Build 32 (issue #23): a session name of its own, so the
				// test's logout never ends a collecting instance's session.
				SynologyApiClient testApi = new SynologyApiClient(
						testHttp, testCfg.username, testCfg.password,
						SynologyConfig.sessionName(null, true,
								SynologyConfig.randomToken()),
						componentLogger(SynologyApiClient.class));
				testApi.login();
				try {
					SimpleJson info = testApi.dsmInfo();
					String model = info.data().get("model").asString("unknown");
					logInfo("Test OK: connected to " + testCfg.host
							+ " (model=" + model + ")");
				} finally {
					testApi.logout();
				}
			} finally {
				testHttp.discard();
			}
		};
	}

	private static ResourceConfig testResourceConfig(
			com.integrien.alive.common.adapter3.TestParam param) {
		if (param == null) {
			return null;
		}
		com.integrien.alive.common.adapter3.config.AdapterConfig adConf =
				param.getAdapterConfig();
		if (adConf == null) {
			return null;
		}
		return adConf.getAdapterInstResource();
	}

	// -----------------------------------------------------------------------
	// Collect-path discovery (framework v2 §22). VCF Ops 9.0.2 never invokes
	// onDiscover() for adapter3-path collectors, so a fresh instance would
	// heartbeat GREEN yet sit at zero resources forever (build-framework #18).
	// Opting in drives enumeration from the top of every collect cycle; the
	// platform de-duplicates by identifying identifier, so the 25 existing
	// devel resources do NOT duplicate (rcOf order/values unchanged from b16).
	// The framework default getDiscoverer() also calls enumerateResources, so
	// onDiscover() stays wired for platforms that do call it — no drift.
	// -----------------------------------------------------------------------

	@Override
	protected boolean discoverOnCollect() {
		return true;
	}

	/**
	 * Build 31 (issue #12): a discovery pass is its own cycle for the
	 * snapshot cache, so a failure cached by a collection cycle stops
	 * blocking only by its back-off, not by a discovery call. Otherwise the
	 * framework default: {@link #enumerateResources(ResourceSink)} into the
	 * discovery result.
	 */
	@Override
	@SuppressWarnings("rawtypes")
	protected com.vcfcf.adapter.spi.VcfCfDiscoverer getDiscoverer() {
		return (cfg, http, param, dr) -> {
			beginCycle();
			enumerateResources(dr::addResource);
		};
	}

	/**
	 * Build 31 (issue #12): marks the start of a collection or discovery
	 * pass in the current configuration's snapshot cache. A no-op before
	 * the first configure.
	 */
	private void beginCycle() {
		Configured c = this.configured;
		if (c != null) {
			c.snapshots.beginCycle();
		}
	}

	/**
	 * Enumerate the Synology resource tree. Driven from the collect path every
	 * cycle (§22) and from {@code onDiscover()} (framework default) — one body,
	 * no drift.
	 *
	 * <p><b>Snapshot reuse (§18).</b> Enumeration now runs each collect cycle, so
	 * it must NOT issue its own API calls. It serves entirely from the per-cycle
	 * {@link #currentSnapshot()} — the same immutable pull the per-resource
	 * collectors use. The snapshot already computes the NFS-export gating
	 * ({@code nfsRulesByShare}: a share is an export iff it has &gt;0 NFS rules)
	 * and the UPS gating ({@code s.ups} non-null iff {@code usb_ups_connect}),
	 * so the resource set enumerated here is byte-identical to build 16's
	 * discoverer output without re-probing.
	 *
	 * <p><b>Unreadable is not invisible.</b> A snapshot refresh failure throws
	 * out of {@code currentSnapshot()} and propagates; the framework treats a
	 * thrown enumeration as a non-fatal rediscovery failure (WARN, keep the
	 * existing resource set) — never a silent zero-resource registration.
	 */
	@Override
	protected void enumerateResources(ResourceSink sink)
			throws InterruptedException, Exception {
		Snapshot s = currentSnapshot();

		// World singleton
		sink.accept(rcOf("SynologyWorld", "Synology World",
				"world_id", "synology_world"));

		// Diskstation singleton
		String serial = s.dsmInfo.data().get("serial").asString("unknown");
		String model = s.dsmInfo.data().get("model").asString("");
		String dsName = model.isEmpty() ? serial : model + " " + serial;
		sink.accept(rcOf("SynologyDiskstation", dsName, "serial", serial));

		SimpleJson storage = s.storage;
		int pools = 0, volumes = 0, disks = 0, caches = 0, luns = 0, exports = 0;

		for (SimpleJson pool : storage.data().get("storagePools").asList()) {
			sink.accept(rcOf("SynologyStoragePool", poolDisplayName(pool),
					"pool_id", pool.get("id").asString()));
			pools++;
		}
		for (SimpleJson vol : storage.data().get("volumes").asList()) {
			String volPath = vol.get("vol_path").asString();
			sink.accept(rcOf("SynologyVolume", volumeDisplayName(vol),
					"volume_id", vol.get("volume_id").asString(volPath)));
			volumes++;
		}
		for (SimpleJson disk : storage.data().get("disks").asList()) {
			String diskId = disk.get("id").asString();
			sink.accept(rcOf("SynologyDisk", disk.get("name").asString(diskId),
					"disk_id", diskId));
			disks++;
		}
		SimpleJson ssdCaches = storage.data().get("ssdCaches");
		if (!ssdCaches.isNull()) {
			for (SimpleJson cache : ssdCaches.asList()) {
				sink.accept(rcOf("SynologySsdCache",
						cacheDisplayName(cache, storage),
						"cache_id", cache.get("id").asString()));
				caches++;
			}
		}

		for (SimpleJson lun : s.lunList.data().get("luns").asList()) {
			String uuid = lun.get("uuid").asString();
			sink.accept(rcOf("SynologyIscsiLun",
					lun.get("name").asString(uuid), "lun_uuid", uuid));
			luns++;
		}

		// NFS exports: a share is an export iff the snapshot kept >0 NFS rules
		// for it (nfsRulesByShare). Same gating as build 16's per-share probe,
		// served from the snapshot — no re-probe.
		for (SimpleJson share : s.shares.data().get("shares").asList()) {
			String name = share.get("name").asString();
			if (s.nfsRulesByShare.containsKey(name)) {
				sink.accept(rcOf("SynologyNfsExport", name, "share_name", name));
				exports++;
			}
		}

		// UPS (optional): present in the snapshot only when usb_ups_connect=true.
		if (s.ups != null) {
			String upsModel = s.ups.data().get("model").asString("UPS");
			sink.accept(rcOf("SynologyUps", upsModel, "ups_model", upsModel));
		}

		logInfo("Synology enumerate: 1 world, 1 diskstation, " + pools
				+ " pools, " + volumes + " volumes, " + disks + " disks, "
				+ caches + " ssd-caches, " + luns + " luns, " + exports
				+ " nfs-exports");
	}

	private ResourceConfig rcOf(String kind, String name, String idKey, String idValue) {
		ResourceKey key = new ResourceKey(name, kind, ADAPTER_KIND);
		key.addIdentifier(new ResourceIdentifierConfig(idKey, idValue, true));
		return new ResourceConfig(key);
	}

	// -----------------------------------------------------------------------
	// getCollector — per-resource dispatch over a per-cycle snapshot
	// -----------------------------------------------------------------------

	@Override
	protected VcfCfCollector<SynologyConfig> getCollector() {
		return new VcfCfCollector<SynologyConfig>() {
			/**
			 * Build 31 (issue #12): the framework calls this exactly once at
			 * the top of every onCollect, before discovery and the
			 * per-resource loop, so it is the start-of-cycle hook for the
			 * snapshot cache. Never asks for the hand-rolled rediscovery
			 * (always false), exactly as the framework default did.
			 */
			@Override
			public boolean needsRediscovery(SynologyConfig cfg) {
				beginCycle();
				return false;
			}

			@Override
			public void collect(SynologyConfig cfg, ManagedHttpClient http,
					ResourceConfig rc, List<MetricData> out, AdapterBase adapter)
					throws InterruptedException, Exception {
				Snapshot snap = currentSnapshot();
				dispatchCollect(rc, snap, out);
			}

			@Override
			public Relationships collectRelationships(SynologyConfig cfg,
					ResourceConfig rc) {
				// Emit the full topology once per cycle, anchored on the World
				// resource (always present, always collected). Returning the full
				// set every cycle mirrors v1's shouldForceUpdateRelationships=true.
				if (!"SynologyWorld".equals(rc.getResourceKind())) {
					return null;
				}
				try {
					return buildRelationships(currentSnapshot());
				} catch (Exception e) {
					logWarn("Relationship build failed: " + e.getMessage());
					return null;
				}
			}

			/**
			 * Build 31 (issue #13): DOWN when the NAS could not be reached
			 * (connect refused, connect or request timeout, unknown host, no
			 * route), found anywhere in the cause chain, including a cached
			 * snapshot failure. ERROR for DSM login refusals, certificate
			 * failures, unreadable payloads and anything else. Before build
			 * 31 only a bare ConnectException was DOWN, and the API client
			 * never threw one (it rethrows every transport failure as an
			 * IOException), so an unreachable NAS was always ERROR.
			 */
			@Override
			public ResourceStatusEnum mapCollectException(Exception e) {
				if (SynologyFailures.isUnreachable(e)) {
					return ResourceStatusEnum.RESOURCE_STATUS_DOWN;
				}
				return ResourceStatusEnum.RESOURCE_STATUS_ERROR;
			}
		};
	}

	/**
	 * Return the snapshot for this cycle, building it on the cycle's first
	 * call (or after {@link #MAX_SNAPSHOT_AGE_MS} in an endless cycle). A
	 * refresh failure (session/REST error) propagates out so the framework
	 * marks the resource ERROR/DOWN, never a silent empty snapshot.
	 *
	 * <p>Build 31 (issue #12): a failed refresh is cached by the
	 * configuration's {@link SnapshotCache}: the first caller of the cycle
	 * (usually the collect-path discovery step) gets a one-line, stackless
	 * copy of the failure, and the remaining resources fail from the same
	 * kind of copy without contacting the NAS (no new login), as does every
	 * call until its back-off has passed. The cache logs one "Synology
	 * snapshot build failed" WARN per attempted build, and the same line
	 * with the original exception and its stack at DEBUG through the logger
	 * stored in {@link Configured}; the framework still logs its own line
	 * for each resource that fails.
	 */
	private Snapshot currentSnapshot() throws Exception {
		Configured c = this.configured;
		if (c == null) {
			throw new IllegalStateException("Synology adapter instance is not "
					+ "configured");
		}
		// Review NIT-3: the login runs inside the read plan, after its own
		// abort check (SnapshotReads.read), not before the first checkpoint.
		return c.snapshots.get(() -> Snapshot.build(c.api, c.reads, this),
				this::logWarn);
	}

	/**
	 * Build 31 (issue #13): called between DSM calls while a snapshot is
	 * built. A thread interrupt ends the build with an
	 * {@link InterruptedException}, which the framework turns into a cycle
	 * abort; Stop Collecting (the framework's abort flag) ends it with a
	 * {@link SnapshotCache.AbortedException}. Neither is cached as a NAS
	 * failure. Without this a stop waited out every remaining call, each up
	 * to three 30 s timeouts against a hung NAS.
	 */
	void checkpoint(String next) throws InterruptedException,
			SnapshotCache.AbortedException {
		if (Thread.currentThread().isInterrupted()) {
			throw new InterruptedException("Synology snapshot build interrupted "
					+ "before " + next);
		}
		if (isAbortRequested()) {
			throw new SnapshotCache.AbortedException("Stop Collecting "
					+ "requested: Synology snapshot build stopped before " + next);
		}
	}

	private void dispatchCollect(ResourceConfig rc, Snapshot snap,
			List<MetricData> out) throws IOException {
		String kind = rc.getResourceKind();
		if (kind == null) return;
		// Build 31 (issue #14): a LUN or NFS export whose list was not read
		// this cycle is ERROR, not "collected nothing"; see
		// SnapshotIsolation.requireListRead.
		snap.requireListRead(kind);
		switch (kind) {
			case "SynologyWorld":
				// No domain metrics on the world (v1 parity — NO_DATA_RECEIVING).
				break;
			case "SynologyDiskstation":
				collectDiskstation(snap, out);
				break;
			case "SynologyStoragePool":
				collectStoragePool(rc, snap, out);
				break;
			case "SynologyVolume":
				collectVolume(rc, snap, out);
				break;
			case "SynologyDisk":
				collectDisk(rc, snap, out);
				break;
			case "SynologySsdCache":
				collectSsdCache(rc, snap, out);
				break;
			case "SynologyIscsiLun":
				collectIscsiLun(rc, snap, out);
				break;
			case "SynologyNfsExport":
				collectNfsExport(rc, snap, out);
				break;
			case "SynologyUps":
				collectUps(rc, snap, out);
				break;
			default:
				logWarn("collect: unknown resource kind " + kind);
		}
	}

	// -----------------------------------------------------------------------
	// MetricData append helpers
	// -----------------------------------------------------------------------

	private static void metric(List<MetricData> out, String key, double value) {
		out.add(new MetricData(new MetricKey(key), System.currentTimeMillis(), value));
	}

	private static void prop(List<MetricData> out, String key, String value) {
		out.add(new MetricData(new MetricKey(true, key),
				System.currentTimeMillis(), value != null ? value : ""));
	}

	// -----------------------------------------------------------------------
	// Per-kind collectors (value semantics preserved from v1)
	// -----------------------------------------------------------------------

	private void collectDiskstation(Snapshot s, List<MetricData> out) {
		SimpleJson dsmInfo = s.dsmInfo;
		SimpleJson sysInfo = s.systemInfo;
		SimpleJson util = s.utilization;
		SimpleJson fan = s.fanSpeed;
		SimpleJson nfs = s.nfsService;

		prop(out, "System|model", dsmInfo.data().get("model").asString(""));
		prop(out, "System|hostname", sysInfo.data().get("sys_name").asString(""));
		prop(out, "System|firmware_version", sysInfo.data().get("firmware_ver").asString(""));
		prop(out, "System|firmware_date", sysInfo.data().get("firmware_date").asString(""));

		metric(out, "System|system_temp", dsmInfo.data().get("temperature").asDouble());
		metric(out, "System|uptime", (double) dsmInfo.data().get("uptime").asLong());

		SimpleJson cpu = util.data().get("cpu");
		metric(out, "CPU|cpu_load_1m", cpu.get("1min_load").asDouble());
		metric(out, "CPU|cpu_load_5m", cpu.get("5min_load").asDouble());
		metric(out, "CPU|cpu_load_15m", cpu.get("15min_load").asDouble());
		metric(out, "CPU|cpu_user_pct", cpu.get("user_load").asDouble());
		metric(out, "CPU|cpu_system_pct", cpu.get("system_load").asDouble());
		metric(out, "CPU|cpu_total_load",
				cpu.get("user_load").asDouble() + cpu.get("system_load").asDouble());

		SimpleJson mem = util.data().get("memory");
		metric(out, "Memory|memory_available", mem.get("avail_real").asDouble());
		metric(out, "Memory|memory_total", mem.get("total_real").asDouble());
		metric(out, "Memory|memory_usage_pct", mem.get("real_usage").asDouble());
		metric(out, "Memory|memory_cached", mem.get("cached").asDouble());
		metric(out, "Memory|swap_usage",
				mem.get("total_swap").asDouble() - mem.get("avail_swap").asDouble());
		metric(out, "Memory|swap_total", mem.get("total_swap").asDouble());

		double rxTotal = 0, txTotal = 0;
		for (SimpleJson nic : util.data().get("network").asList()) {
			rxTotal += nic.get("rx").asDouble();
			txTotal += nic.get("tx").asDouble();
		}
		metric(out, "Network|net_rx_bytes", rxTotal);
		metric(out, "Network|net_tx_bytes", txTotal);
		// Build 31 (issue #14): a key whose optional endpoint was not read
		// this cycle is skipped, never published as 0, "unknown" or "false".
		if (s.isRead(SnapshotReads.NICS)) {
			metric(out, "Network|nic_count", (double) s.networkInterfaces.data().size());
		}

		if (s.isRead(SnapshotReads.FAN)) {
			prop(out, "Fan|fan_status", fan.data().get("cool_fan").asString("unknown"));
			prop(out, "Fan|fan_speed_mode", fan.data().get("dual_fan_speed").asString("unknown"));
		}

		if (s.isRead(SnapshotReads.NFS_SERVICE)) {
			prop(out, "NFS|nfs_enabled", nfs.data().get("enable_nfs").asBoolean() ? "true" : "false");
			prop(out, "NFS|nfs_v4_enabled", nfs.data().get("enable_nfs_v4").asBoolean() ? "true" : "false");
		}

		SimpleJson nfsUtil = util.data().get("nfs");
		if (!nfsUtil.isNull() && nfsUtil.size() > 0) {
			SimpleJson nfsRow = nfsUtil.get(0);
			metric(out, "NFS|nfs_total_ops", nfsRow.get("total_OPS").asDouble());
			metric(out, "NFS|nfs_read_ops", nfsRow.get("read_OPS").asDouble());
			metric(out, "NFS|nfs_write_ops", nfsRow.get("write_OPS").asDouble());
			metric(out, "NFS|nfs_max_latency", nfsRow.get("total_max_latency").asDouble());
		}

		if (s.isRead(SnapshotReads.CONNECTIONS)) {
			int nfsClients = 0;
			for (SimpleJson conn : s.connections.data().get("items").asList()) {
				if ("NFS".equals(conn.get("protocol").asString())) {
					nfsClients++;
				}
			}
			metric(out, "NFS|nfs_client_count", (double) nfsClients);
		}
	}

	private void collectStoragePool(ResourceConfig rc, Snapshot s, List<MetricData> out) {
		String poolId = getIdentifier(rc, "pool_id");
		SimpleJson pool = s.findPool(poolId);
		if (pool == null) {
			logWarn("collect: storage pool " + poolId + " absent from snapshot");
			return;
		}

		SimpleJson size = pool.get("size");
		double total = size.get("total").asDouble();
		double used = size.get("used").asDouble();
		metric(out, "Capacity|total_bytes", total);
		metric(out, "Capacity|used_bytes", used);
		metric(out, "Capacity|usage_pct", total > 0 ? (used / total) * 100.0 : 0.0);

		prop(out, "Configuration|raid_type", pool.get("raidType").asString(""));
		prop(out, "Configuration|status", pool.get("status").asString(""));
		prop(out, "Configuration|pool_path", pool.get("pool_path").asString(""));
		prop(out, "Configuration|device_type", pool.get("device_type").asString(""));

		SimpleJson disks = pool.get("disks");
		metric(out, "Configuration|disk_count",
				(double) (disks.isNull() ? 0 : disks.size()));
	}

	private void collectVolume(ResourceConfig rc, Snapshot s, List<MetricData> out) {
		String volId = getIdentifier(rc, "volume_id");
		SimpleJson vol = s.findVolume(volId);
		if (vol == null) {
			logWarn("collect: volume " + volId + " absent from snapshot");
			return;
		}
		String volPath = vol.get("vol_path").asString();

		SimpleJson size = vol.get("size");
		double total = size.get("total").asDouble();
		double free = size.get("free").asDouble();
		metric(out, "Capacity|total_bytes", total);
		metric(out, "Capacity|free_bytes", free);
		metric(out, "Capacity|usage_pct", total > 0 ? ((total - free) / total) * 100.0 : 0.0);

		prop(out, "Configuration|volume_path", volPath);
		prop(out, "Configuration|fs_type", vol.get("fs_type").asString(""));
		prop(out, "Configuration|status", vol.get("status").asString(""));
		prop(out, "Configuration|description", vol.get("deploy_path").asString(""));

		// Volume IO from Utilization (keyed by vol_path stripped of leading /)
		String volIoKey = volPath.startsWith("/") ? volPath.substring(1) : volPath;
		SimpleJson io = s.volIoByName.get(volIoKey);
		if (io != null) {
			metric(out, "IO|read_bytes", io.get("read_byte").asDouble());
			metric(out, "IO|write_bytes", io.get("write_byte").asDouble());
			metric(out, "IO|read_iops", io.get("read_access").asDouble());
			metric(out, "IO|write_iops", io.get("write_access").asDouble());
			metric(out, "IO|utilization_pct", io.get("utilization").asDouble());
		}

		// Cache hit rates on Volume (from ssdCaches[] matched by path == vol_path)
		SimpleJson ssdCaches = s.storage.data().get("ssdCaches");
		if (!ssdCaches.isNull()) {
			for (SimpleJson cache : ssdCaches.asList()) {
				if (volPath.equals(cache.get("path").asString(""))) {
					prop(out, "Cache|cache_enabled", "true");
					prop(out, "Cache|cache_status", cache.get("status").asString(""));
					metric(out, "Cache|cache_read_hit_rate", cache.get("hit_rate").asDouble());
					metric(out, "Cache|cache_write_hit_rate", cache.get("hit_rate_write").asDouble());
					break;
				}
			}
		}
	}

	private void collectDisk(ResourceConfig rc, Snapshot s, List<MetricData> out) {
		String diskId = getIdentifier(rc, "disk_id");
		SimpleJson disk = s.findDisk(diskId);
		if (disk == null) {
			logWarn("collect: disk " + diskId + " absent from snapshot");
			return;
		}
		String diskName = disk.get("name").asString(diskId);

		prop(out, "Hardware|display_name", diskName);
		prop(out, "Hardware|model", disk.get("model").asString(""));
		prop(out, "Hardware|firmware", disk.get("firm").asString(""));
		prop(out, "Hardware|serial", disk.get("serial").asString(""));
		prop(out, "Hardware|vendor", disk.get("vendor").asString(""));
		prop(out, "Hardware|disk_type", disk.get("diskType").asString(""));
		prop(out, "Hardware|disk_code", disk.get("disk_code").asString(""));
		prop(out, "Hardware|is_ssd", disk.get("isSsd").asBoolean() ? "true" : "false");
		prop(out, "Hardware|slot_id", disk.get("slot_id").asString(""));
		prop(out, "Hardware|size_bytes", disk.get("size_total").asString("0"));

		metric(out, "Health|temperature", disk.get("temp").asDouble());
		prop(out, "Health|smart_status", disk.get("smart_status").asString(""));
		metric(out, "Health|unc_sectors", disk.get("unc").asDouble());
		metric(out, "Health|remain_life", disk.get("remain_life").asDouble());

		String device = disk.get("device").asString("");
		if (device.startsWith("/dev/")) device = device.substring(5);
		SimpleJson io = s.diskIoByDevice.get(device);
		if (io != null) {
			metric(out, "IO|read_bytes", io.get("read_byte").asDouble());
			metric(out, "IO|write_bytes", io.get("write_byte").asDouble());
			metric(out, "IO|read_iops", io.get("read_access").asDouble());
			metric(out, "IO|write_iops", io.get("write_access").asDouble());
			metric(out, "IO|utilization_pct", io.get("utilization").asDouble());
		}
	}

	private void collectSsdCache(ResourceConfig rc, Snapshot s, List<MetricData> out) {
		String cacheId = getIdentifier(rc, "cache_id");
		SimpleJson cache = s.findCache(cacheId);
		if (cache == null) {
			logWarn("collect: ssd-cache " + cacheId + " absent from snapshot");
			return;
		}

		metric(out, "HitRate|read_hit_rate", cache.get("hit_rate").asDouble());
		metric(out, "HitRate|write_hit_rate", cache.get("hit_rate_write").asDouble());

		SimpleJson size = cache.get("size");
		metric(out, "Capacity|total_bytes", size.get("total").asDouble());
		metric(out, "Capacity|occupied_bytes", size.get("occupied").asDouble());
		metric(out, "Capacity|reusable_bytes", size.get("reusable").asDouble());
		metric(out, "Capacity|memory_used", cache.get("memory").asDouble());

		prop(out, "Configuration|mode", cache.get("mode").asString(""));
		prop(out, "Configuration|status", cache.get("status").asString(""));
		prop(out, "Configuration|mount_volume", cache.get("mountSpaceId").asString(""));
		prop(out, "Configuration|device_type", cache.get("device_type").asString(""));
		prop(out, "Configuration|skip_seq_io",
				cache.get("skipSeqIO").asBoolean() ? "true" : "false");

		SimpleJson cacheDisks = cache.get("disks");
		int diskCount = cacheDisks.isNull() ? 0 : cacheDisks.size();
		prop(out, "Hardware|disk_count", String.valueOf(diskCount));
		StringBuilder members = new StringBuilder();
		if (!cacheDisks.isNull()) {
			for (SimpleJson d : cacheDisks.asList()) {
				if (members.length() > 0) members.append(", ");
				members.append(d.asString());
			}
		}
		prop(out, "Hardware|disk_members", members.toString());
		double totalBytes = size.get("total").asDouble();
		prop(out, "Hardware|total_capacity",
				String.format("%.1f GB", totalBytes / (1024.0 * 1024.0 * 1024.0)));
		prop(out, "Hardware|disk_failure_count",
				String.valueOf(cache.get("disk_failure_number").asLong()));
	}

	private void collectIscsiLun(ResourceConfig rc, Snapshot s, List<MetricData> out)
			throws IOException {
		String uuid = getIdentifier(rc, "lun_uuid");
		// Build 31 (issue #14): dispatchCollect has already failed this LUN
		// when the LUN list was not read this cycle.
		SimpleJson lun = s.findLun(uuid);
		if (lun == null) {
			logWarn("collect: iscsi-lun " + uuid + " absent from snapshot");
			return;
		}
		String name = lun.get("name").asString();

		prop(out, "Configuration|name", name);
		prop(out, "Configuration|size_bytes", String.valueOf(lun.get("size").asLong()));
		prop(out, "Configuration|location", lun.get("location").asString(""));
		prop(out, "Configuration|type", lun.get("type_str").asString(""));

		if (s.isRead(SnapshotReads.TARGETS)) {
			String iqn = s.targetIqnByName.get(name);
			prop(out, "Configuration|target_iqn", iqn != null ? iqn : "");
			String enabled = s.targetEnabledByName.get(name);
			prop(out, "Configuration|target_enabled", enabled != null ? enabled : "");
			String portals = s.targetPortalsByName.get(name);
			prop(out, "Configuration|network_portals", portals != null ? portals : "");
		}

		SimpleJson io = s.lunIoByUuid.get(uuid);
		if (io != null) {
			metric(out, "IO|read_iops", io.get("read_iops").asDouble());
			metric(out, "IO|write_iops", io.get("write_iops").asDouble());
			metric(out, "IO|read_throughput", io.get("read_throughput").asDouble());
			metric(out, "IO|write_throughput", io.get("write_throughput").asDouble());
			metric(out, "IO|read_latency", io.get("read_latency").asDouble());
			metric(out, "IO|write_latency", io.get("write_latency").asDouble());
		}

	}

	private void collectNfsExport(ResourceConfig rc, Snapshot s, List<MetricData> out)
			throws IOException {
		String name = getIdentifier(rc, "share_name");
		// Build 31 (issue #14): dispatchCollect has already failed this
		// export when the share list was not read this cycle. Review B2: and
		// when this share's NFS rule probe failed, it is ERROR too, never
		// rule_count = 0 for rules that were not read.
		s.requireNfsProbe(name);
		SimpleJson share = s.findShare(name);
		if (share == null) {
			logWarn("collect: nfs-export " + name + " absent from snapshot");
			return;
		}

		SimpleJson rules = s.nfsRulesByShare.get(name);
		int ruleCount = rules != null ? rules.data().get("rule").size() : 0;

		String volPath = share.get("vol_path").asString("");
		prop(out, "Configuration|export_path", volPath + "/" + name);
		prop(out, "Configuration|volume_path", volPath);
		prop(out, "Configuration|description", share.get("desc").asString(""));
		prop(out, "Configuration|quota_value_mib",
				String.valueOf(share.get("quota_value").asLong()));
		prop(out, "Access|cow_enabled",
				share.get("enable_share_cow").asBoolean() ? "true" : "false");
		prop(out, "Access|compress_enabled",
				share.get("enable_share_compress").asBoolean() ? "true" : "false");
		prop(out, "Access|rule_count", String.valueOf(ruleCount));

		StringBuilder clients = new StringBuilder();
		if (rules != null) {
			for (SimpleJson rule : rules.data().get("rule").asList()) {
				if (clients.length() > 0) clients.append(", ");
				clients.append(rule.get("client").asString(""));
			}
		}
		prop(out, "Access|allowed_clients", clients.toString());

		metric(out, "Capacity|size_used_mib", share.get("share_quota_used").asDouble());
		metric(out, "Capacity|size_logical_mib",
				share.get("share_quota_logical_size").asDouble());
		long quota = share.get("quota_value").asLong();
		double used = share.get("share_quota_used").asDouble();
		metric(out, "Capacity|quota_usage_pct", quota > 0 ? (used / quota) * 100.0 : 0.0);

		if (s.isRead(SnapshotReads.CONNECTIONS)) {
			metric(out, "Clients|active_client_count",
					(double) s.nfsClientsByShare.getOrDefault(name, 0));
		}
	}

	private void collectUps(ResourceConfig rc, Snapshot s, List<MetricData> out) {
		SimpleJson ups = s.ups;
		if (ups == null) {
			logWarn("collect: UPS resource present but UPS data absent from snapshot");
			return;
		}
		boolean connected = ups.data().get("usb_ups_connect").asBoolean();
		metric(out, "Battery|charge_pct", ups.data().get("charge").asDouble());
		metric(out, "Battery|runtime_seconds", ups.data().get("runtime").asDouble());
		prop(out, "Status|status", ups.data().get("status").asString(""));
		prop(out, "Status|mode", ups.data().get("mode").asString(""));
		prop(out, "Status|connected", connected ? "true" : "false");
	}

	// -----------------------------------------------------------------------
	// Relationships: internal parent/child tree (no foreign stitching)
	// -----------------------------------------------------------------------

	private Relationships buildRelationships(Snapshot s) {
		RelationshipBuilder rb = new RelationshipBuilder(ADAPTER_KIND);

		// Build 31 (issue #14): own-adapter parents are full-set (the platform
		// replaces a parent's children with what is emitted). A Volume's
		// children come from the LUN list, the share list and the SSD caches;
		// when the LUN or share list was not read this cycle, emitting the
		// Volume with the children that are known would drop its other edges
		// for the cycle. So no Volume is a parent this cycle; its existing
		// edges are kept. Review NIT-R3: a failed NFS rule probe freezes only
		// the Volume its share sits on; the other Volumes refresh.
		Map<String, String> volPathByShare = new HashMap<>();
		for (SimpleJson share : s.shares.data().get("shares").asList()) {
			String name = share.get("name").asString();
			if (name != null) {
				volPathByShare.put(name, share.get("vol_path").asString());
			}
		}
		final SnapshotIsolation.VolumeChildren volumeChildren =
				SnapshotIsolation.volumeChildren(s.unread, s.nfsProbeFailed,
						volPathByShare);
		if (!volumeChildren.allKnown()) {
			logInfo("Relationships: Volume child edges (LUN, NFS export, SSD "
					+ "cache) left unchanged this cycle for "
					+ (volumeChildren.noneKnown() ? "every Volume"
							: "the Volume(s) at " + volumeChildren.frozenVolumes())
					+ ": " + (s.unread.isEmpty() ? "" : s.unread.keySet()
							+ " not read")
					+ (s.nfsProbeFailed.isEmpty() ? "" : (s.unread.isEmpty()
							? "" : ", ") + "NFS rule probe failed for share(s) "
							+ s.nfsProbeFailed.keySet()));
		}

		// Build 31 (issue #19): the internal tree is joined by
		// SynologyTopology (SDK-free, null-safe joins, one try block per
		// pool, LUN, export and SSD cache), so one object with a missing
		// field costs its own edges instead of every relationship of the
		// cycle. Same edges, order, names and identifiers as build 30.
		SynologyTopology.Input<SimpleJson> in = new SynologyTopology.Input<>();
		in.dsmInfo = s.dsmInfo;
		in.storage = s.storage;
		in.lunList = s.lunList;
		in.shares = s.shares;
		in.ups = s.ups;
		in.nfsExports = s.nfsRulesByShare.keySet();
		in.volumeChildren = volumeChildren;
		SynologyTopology.build(SJ, in,
				(parent, child) -> rb.parent(key(rb, parent), key(rb, child)),
				this::logWarn, this::logInfo);

		// --- Optional Datastore cross-link (build 16, v1 parity) ---
		// Resolve real VMWARE Datastores by DataStrorePath and make each LUN /
		// NFS export a child of the Datastore that backs it. Skipped silently
		// when the Suite API transport is unavailable (stitcher == null).
		emitDatastoreCrossLink(rb, s);

		logInfo("Relationships built: internal World>Diskstation>Pool>"
				+ "Volume>{LUN,NFS,SSDCache,Disk} tree"
				+ (stitcher != null ? " + Datastore cross-link" : ""));
		return rb.build();
	}

	/**
	 * Emit v1's informational {@code Datastore → {LUN, NFS export}} cross-link.
	 *
	 * <p>Loads the real VMWARE Datastores once (cached by the resolver), then for
	 * each iSCSI LUN computes its VMFS extent path ({@code "VMFS:|naa...|"}) and
	 * for each NFS export computes one {@code <ip>/<volPath>/<share>} path per
	 * connected NAS IP; a {@code DataStrorePath} that resolves to an existing
	 * Datastore yields a {@code parentForeign(datastore, child)} edge. No match
	 * means no edge (no phantom Datastore). All counts are logged at INFO.
	 *
	 * <p>No-op when {@code stitcher == null} (Suite API unavailable). Any failure
	 * is caught and logged WARN — the internal topology already built must still
	 * be returned, so a cross-link fault never costs the cycle its relationships.
	 */
	private void emitDatastoreCrossLink(RelationshipBuilder rb, Snapshot s) {
		SynologyStitcher st = this.stitcher;
		if (st == null) return;
		try {
			int datastoreCount = st.loadDatastores();
			int lunMatches = 0;
			int nfsMatches = 0;

			// iSCSI LUN → Datastore via NAA transform.
			for (SimpleJson lun : s.lunList.data().get("luns").asList()) {
				String uuid = lun.get("uuid").asString();
				if (uuid == null || uuid.isEmpty()) continue;
				String path = SynologyStitcher.lunDataStorePath(uuid);
				List<ResourceKey> matches = st.matchByPath(path);
				if (matches.isEmpty()) continue;
				ResourceKey lunKey = rb.resource("SynologyIscsiLun",
						lun.get("name").asString(uuid), "lun_uuid", uuid);
				// Standardized direction (1.0.0.18): the VMWARE Datastore is the
				// PARENT of its backing LUN — the original design intent and the
				// same shape NFS already used. parentForeign emits
				// setRelationships(datastore, {lun}); DEF-003 (closed) proved the
				// platform scopes that per-reporting-adapter, so the Datastore
				// keeps its VMWARE children and gains the synology child (no
				// clobber). One path can back N datastores (one per vCenter view,
				// same NAA, distinct identity) — bind every copy.
				for (ResourceKey ds : matches) {
					rb.parentForeign(ds, lunKey);
					lunMatches++;
				}
			}

			// NFS Export → Datastore via <nas_ip>/<volPath>/<share> path. NAS IPs
			// come from the per-cycle snapshot's networkInterfaces (no live call).
			List<String> nasIps =
					SynologyStitcher.connectedNasIps(s.networkInterfaces);
			for (SimpleJson share : s.shares.data().get("shares").asList()) {
				String name = share.get("name").asString();
				SimpleJson rules = s.nfsRulesByShare.get(name);
				if (rules == null || rules.data().get("rule").size() == 0) continue;
				String volPath = share.get("vol_path").asString("");
				ResourceKey exportKey = null;
				// Two NAS IPs can resolve to the same DataStrorePath, yielding the
				// same Datastore match twice; dedup per export so we never emit
				// setRelationships(ds, {export, export}) with a duplicate child.
				java.util.Set<ResourceKey> linkedDs = new java.util.HashSet<>();
				for (String ip : nasIps) {
					String path = SynologyStitcher.nfsDataStorePath(ip, volPath, name);
					List<ResourceKey> matches = st.matchByPath(path);
					if (matches.isEmpty()) continue;
					if (exportKey == null) {
						exportKey = rb.resource("SynologyNfsExport", name,
								"share_name", name);
					}
					// One path can back N datastores (one per vCenter view) — bind
					// every copy with the Datastore as parent (DEF-003-safe).
					for (ResourceKey ds : matches) {
						if (!linkedDs.add(ds)) continue;
						rb.parentForeign(ds, exportKey);
						nfsMatches++;
					}
				}
			}

			logInfo("Datastore cross-link: " + datastoreCount
					+ " datastores loaded, " + lunMatches + " LUN matches, "
					+ nfsMatches + " NFS matches");
		} catch (Exception e) {
			// Build 32 (issue #27): redacted before it is logged.
			logWarn("Datastore cross-link failed (internal topology unaffected): "
					+ SynologyRedaction.redact(e.getMessage()));
		}
	}

	// -----------------------------------------------------------------------
	// Display name helpers
	// -----------------------------------------------------------------------

	// Build 31 (issue #19): one definition of each display name, shared with
	// SynologyTopology so enumeration and relationships cannot drift.

	private static String poolDisplayName(SimpleJson pool) {
		return SynologyTopology.poolName(SJ, pool);
	}

	private static String volumeDisplayName(SimpleJson vol) {
		return SynologyTopology.volumeName(SJ, vol);
	}

	private static String cacheDisplayName(SimpleJson cache, SimpleJson storage) {
		return SynologyTopology.cacheName(SJ, cache, storage);
	}

	/** Build 31 (issue #19): the relationship key of a topology object. */
	private static ResourceKey key(RelationshipBuilder rb, SynologyTopology.Obj o) {
		return rb.resource(o.kind, o.name, o.idKey, o.id);
	}

	/** Build 31 (issue #19): {@link SynologyTopology}'s view of {@link SimpleJson}. */
	private static final SynologyTopology.Json<SimpleJson> SJ =
			new SynologyTopology.Json<SimpleJson>() {
				@Override
				public SimpleJson get(SimpleJson node, String key) {
					return node.get(key);
				}

				@Override
				public String str(SimpleJson node) {
					return node.asString();
				}

				@Override
				public long num(SimpleJson node) {
					return node.asLong();
				}

				@Override
				public boolean bool(SimpleJson node) {
					return node.asBoolean();
				}

				@Override
				public List<SimpleJson> list(SimpleJson node) {
					return node.asList();
				}

				@Override
				public boolean isNull(SimpleJson node) {
					return node.isNull();
				}
			};

	// -----------------------------------------------------------------------
	// onDiscard
	// -----------------------------------------------------------------------

	@Override
	public void onDiscard() {
		Configured c = this.configured;
		SuiteApiStitcher s = this.suiteStitcher;
		this.suiteStitcher = null;
		this.stitcher = null;
		this.configured = null;
		// Build 31 (issue #21): the same teardown as a reconfigure. The HTTP
		// client is left to super.onDiscard(), which discards and clears it.
		releaseConfiguration("instance discarded", c, s);
		super.onDiscard();
	}

	// -----------------------------------------------------------------------
	// Per-cycle API snapshot
	// -----------------------------------------------------------------------

	/**
	 * One immutable pull of the Synology DSM API per cycle, shared across the
	 * per-resource collect calls. Built by {@link #build(SynologyApiClient,
	 * SynologyAdapter)} which logs an INFO breadcrumb of the resource counts.
	 */
	private static final class Snapshot {
		// Build 31 (issue #13): no builtAt here any more. It was a field
		// initializer, stamped before the API calls ran; SnapshotCache now
		// stamps the time when build() returns.

		SimpleJson dsmInfo;
		SimpleJson systemInfo;
		SimpleJson utilization;
		SimpleJson fanSpeed;
		SimpleJson nfsService;
		SimpleJson storage;
		SimpleJson lunList;
		SimpleJson targets;
		SimpleJson networkInterfaces;
		SimpleJson connections;
		SimpleJson shares;
		SimpleJson ups; // nullable: UPS optional

		/**
		 * Build 31 (issue #14): optional endpoints not read this cycle,
		 * endpoint to reason. Their dependent keys, objects and edges are
		 * skipped; the field holds an empty payload.
		 */
		Map<String, String> unread = new java.util.LinkedHashMap<>();

		boolean isRead(String endpoint) {
			return !unread.containsKey(endpoint);
		}

		/**
		 * Throws when {@code kind} depends on a list that was not read this
		 * cycle; see {@link SnapshotIsolation#requireListRead}.
		 */
		void requireListRead(String kind) throws IOException {
			SnapshotIsolation.requireListRead(unread, kind);
		}

		/**
		 * Review B2: throws when this share's NFS rule probe failed this
		 * cycle; see {@link SnapshotIsolation#requireNfsProbe}.
		 */
		void requireNfsProbe(String share) throws IOException {
			SnapshotIsolation.requireNfsProbe(nfsProbeFailed, share);
		}

		// Derived lookups
		final Map<String, SimpleJson> diskIoByDevice = new HashMap<>();
		final Map<String, SimpleJson> volIoByName = new HashMap<>();
		final Map<String, SimpleJson> lunIoByUuid = new HashMap<>();
		final Map<String, String> targetIqnByName = new HashMap<>();
		final Map<String, String> targetEnabledByName = new HashMap<>();
		final Map<String, String> targetPortalsByName = new HashMap<>();
		final Map<String, SimpleJson> nfsRulesByShare = new HashMap<>();
		/**
		 * Review B2: shares whose NFS rule probe failed this cycle, share
		 * name to reason. Not known to be exports or not: an existing export
		 * on one is ERROR and Volume edges are left unchanged.
		 */
		final Map<String, String> nfsProbeFailed = new java.util.LinkedHashMap<>();
		final Map<String, Integer> nfsClientsByShare = new HashMap<>();

		static Snapshot build(SynologyApiClient api, SnapshotReads reads,
				SynologyAdapter adapter) throws Exception {
			Snapshot s = new Snapshot();
			// Build 31: SnapshotReads logs in if needed and runs the abort
			// checkpoint before the login (review NIT-3) and before each DSM
			// call (issue #13), and fails the build only on the four required
			// endpoints (issue #14); an optional endpoint not read holds an
			// empty payload and is listed in s.unread.
			SnapshotReads.Result<SimpleJson> r = reads.read(api,
					SimpleJson.parse("{}"), adapter::checkpoint,
					adapter::logWarn, adapter::logInfo);
			s.dsmInfo = r.dsmInfo;
			s.systemInfo = r.systemInfo;
			s.utilization = r.utilization;
			s.fanSpeed = r.fanSpeed;
			s.nfsService = r.nfsService;
			s.storage = r.storage;
			s.lunList = r.lunList;
			s.targets = r.targets;
			s.networkInterfaces = r.networkInterfaces;
			s.connections = r.connections;
			s.shares = r.shares;
			s.unread = r.unread;

			// NIT-1 (build-15): contract-assert one required field on the two
			// critical endpoints that drive the diskstation singleton's metrics.
			// SimpleJson is null-tolerant (asDouble()->0.0), so a "success-shaped
			// but empty" {success:true,data:{}} payload would otherwise publish
			// cpu_load_1m=0.0 / system_temp=0.0 sentinels on a GREEN instance
			// (empty-as-readable). DSM contract: a healthy getinfo always carries
			// a non-null model and a healthy Utilization.get always carries cpu.
			// Surface a hollow-but-200 payload as ERROR, not a 0.0 sentinel.
			if (s.dsmInfo.data().get("model").isNull()) {
				throw new IOException("DSM.Info getinfo returned a 200 payload "
						+ "with no 'model' field — treating as unreadable (no sentinel "
						+ "metrics published)");
			}
			if (s.utilization.data().get("cpu").isNull()) {
				throw new IOException("Core.System.Utilization get returned a 200 "
						+ "payload with no 'cpu' field — treating as unreadable (no "
						+ "sentinel metrics published)");
			}

			adapter.checkpoint("Core.ExternalDevice.UPS");
			try {
				SimpleJson ups = api.upsGet();
				if (ups.data().get("usb_ups_connect").asBoolean()) {
					s.ups = ups;
				}
			} catch (InterruptedException ie) {
				throw ie;
			} catch (Exception e) {
				// Build 31 (issues #12, #13, review NIT-4): a refused re-login,
				// or a NAS that stopped accepting connections or answering,
				// fails the build, as for every other optional endpoint
				// (SynologyFailures.endsBuild).
				if (SynologyFailures.endsBuild(e)) {
					throw e;
				}
				adapter.logInfo("Snapshot: UPS not available: " + e.getMessage());
			}

			s.buildLookups(api, adapter);

			int poolCount = s.storage.data().get("storagePools").asList().size();
			int volCount = s.storage.data().get("volumes").asList().size();
			int diskCount = s.storage.data().get("disks").asList().size();
			int cacheCount = s.storage.data().get("ssdCaches").isNull() ? 0
					: s.storage.data().get("ssdCaches").asList().size();
			int lunCount = s.lunList.data().get("luns").asList().size();
			adapter.logInfo("Synology API snapshot: " + poolCount + " pools / "
					+ volCount + " volumes / " + diskCount + " disks / "
					+ cacheCount + " ssd-caches / " + lunCount + " luns / "
					+ s.nfsRulesByShare.size() + " nfs-exports / ups="
					+ (s.ups != null)
					+ (s.nfsProbeFailed.isEmpty() ? "" : " / nfs probe failed: "
							+ s.nfsProbeFailed.keySet())
					+ (s.unread.isEmpty() ? "" : " / not read: " + s.unread.keySet()));
			if (diskCount == 0) {
				adapter.logWarn("Synology API snapshot: 0 disks returned from "
						+ "storage load_info — expected a populated DiskStation");
			}
			return s;
		}

		private void buildLookups(SynologyApiClient api, SynologyAdapter adapter)
				throws Exception {
			SimpleJson diskUtil = utilization.data().get("disk");
			if (!diskUtil.isNull()) {
				for (SimpleJson d : diskUtil.get("disk").asList()) {
					diskIoByDevice.put(d.get("device").asString(), d);
				}
			}
			SimpleJson spaceUtil = utilization.data().get("space");
			if (!spaceUtil.isNull()) {
				for (SimpleJson v : spaceUtil.get("volume").asList()) {
					volIoByName.put(v.get("display_name").asString(), v);
				}
			}
			SimpleJson lunUtil = utilization.data().get("lun");
			if (!lunUtil.isNull()) {
				for (SimpleJson l : lunUtil.asList()) {
					lunIoByUuid.put(l.get("uuid").asString(), l);
				}
			}
			for (SimpleJson t : targets.data().get("targets").asList()) {
				String tName = t.get("name").asString();
				targetIqnByName.put(tName, t.get("iqn").asString());
				targetEnabledByName.put(tName,
						t.get("is_enabled").asBoolean() ? "true" : "false");
				StringBuilder portals = new StringBuilder();
				SimpleJson np = t.get("network_portals");
				if (!np.isNull()) {
					for (SimpleJson p : np.asList()) {
						if (portals.length() > 0) portals.append(", ");
						portals.append(p.asString());
					}
				}
				targetPortalsByName.put(tName, portals.toString());
			}
			// NFS rule probe per share (identifies a share as an NFS export).
			// A probe failure never fakes an export or its absence: the share
			// is recorded in nfsProbeFailed (review B2), so an existing export
			// on it is ERROR and Volume edges are left unchanged this cycle.
			// See NfsProbes.
			List<String> shareNames = new ArrayList<>();
			for (SimpleJson share : shares.data().get("shares").asList()) {
				shareNames.add(share.get("name").asString());
			}
			NfsProbes.Result<SimpleJson> probes = NfsProbes.run(shareNames,
					api::nfsSharePrivilege,
					rules -> rules.data().get("rule").size(),
					adapter::checkpoint, adapter::logWarn);
			nfsRulesByShare.putAll(probes.rulesByShare);
			nfsProbeFailed.putAll(probes.failed);
			for (SimpleJson conn : connections.data().get("items").asList()) {
				if ("NFS".equals(conn.get("protocol").asString())) {
					String descr = conn.get("descr").asString("");
					if (!"-".equals(descr) && !descr.isEmpty()) {
						nfsClientsByShare.merge(descr, 1, Integer::sum);
					}
				}
			}
		}

		SimpleJson findPool(String poolId) {
			for (SimpleJson p : storage.data().get("storagePools").asList()) {
				// Build 31 (issue #19): constant first, a pool without id never matches.
				if (poolId != null && poolId.equals(p.get("id").asString())) return p;
			}
			return null;
		}

		SimpleJson findVolume(String volId) {
			for (SimpleJson v : storage.data().get("volumes").asList()) {
				// Build 31 (issue #19): a volume with neither volume_id nor
				// vol_path has no identity and never matches.
				if (volId != null && volId.equals(SynologyTopology.volumeId(SJ, v))) return v;
			}
			return null;
		}

		SimpleJson findDisk(String diskId) {
			for (SimpleJson d : storage.data().get("disks").asList()) {
				if (diskId != null && diskId.equals(d.get("id").asString())) return d;
			}
			return null;
		}

		SimpleJson findCache(String cacheId) {
			SimpleJson caches = storage.data().get("ssdCaches");
			if (caches.isNull()) return null;
			for (SimpleJson c : caches.asList()) {
				if (cacheId != null && cacheId.equals(c.get("id").asString())) return c;
			}
			return null;
		}

		SimpleJson findLun(String uuid) {
			for (SimpleJson l : lunList.data().get("luns").asList()) {
				if (uuid != null && uuid.equals(l.get("uuid").asString())) return l;
			}
			return null;
		}

		SimpleJson findShare(String name) {
			for (SimpleJson sh : shares.data().get("shares").asList()) {
				if (name != null && name.equals(sh.get("name").asString())) return sh;
			}
			return null;
		}
	}
}
