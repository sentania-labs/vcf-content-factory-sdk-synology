package com.vcfcf.adapters.synology;

import com.vcfcf.adapter.VcfCfAdapter;
import com.vcfcf.adapter.http.HttpClientBuilder;
import com.vcfcf.adapter.http.ManagedHttpClient;
import com.vcfcf.adapter.json.SimpleJson;
import com.vcfcf.adapter.retry.RetryPolicy;
import com.vcfcf.adapter.spi.VcfCfCollector;
import com.vcfcf.adapter.spi.VcfCfDiscoverer;
import com.vcfcf.adapter.spi.VcfCfTester;
import com.vcfcf.adapter.stitch.RelationshipBuilder;

import com.integrien.alive.common.adapter3.AdapterBase;
import com.integrien.alive.common.adapter3.MetricData;
import com.integrien.alive.common.adapter3.MetricKey;
import com.integrien.alive.common.adapter3.Relationships;
import com.integrien.alive.common.adapter3.ResourceKey;
import com.integrien.alive.common.adapter3.ResourceStatus;
import com.integrien.alive.common.adapter3.config.ResourceConfig;
import com.integrien.alive.common.adapter3.config.ResourceIdentifierConfig;
import com.integrien.alive.common.util.CommonConstants.ResourceStatusEnum;

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
 * {@code com.vcfcf.adapter.spi} roles: {@link VcfCfTester},
 * {@link VcfCfDiscoverer}, {@link VcfCfCollector}. No {@code com.vmware.tvs.*},
 * no {@code Resource}/{@code ResourceCollection}, no JAX-WS. Transport is the
 * framework {@link ManagedHttpClient} over the existing DSM Web API client
 * ({@link SynologyApiClient}); auth/session logic carries over functionally.
 *
 * <p><b>No stitching.</b> Synology v1 has no foreign-resource stitching (golden
 * baseline §3 confirms: zero Synology-namespaced data on any VMWARE resource).
 * The v1 {@code ForeignResourceResolver}/{@code stitchDatastores} path produced
 * no landing data and is dropped — no {@code SuiteApiStitcher}. Parity bar is the
 * 25 own resources / 136 metrics in the golden baseline.
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
 * (UPS absent, NFS rule probe) are logged WARN/INFO and the affected resource is
 * skipped — no sentinel values are pushed for state that could not be read.
 */
public final class SynologyAdapter extends VcfCfAdapter<SynologyConfig> {

	private static final String ADAPTER_KIND = "synology_diskstation";

	private volatile SynologyApiClient api;

	/**
	 * Per-cycle snapshot of the Synology API responses, shared across all
	 * per-resource {@code collect()} calls within one collection cycle. Refreshed
	 * at most once per {@code minRefreshIntervalMs}. {@code volatile} for
	 * cross-thread visibility (collect runs single-threaded per instance, but the
	 * field may be read by the relationship pass).
	 */
	private volatile Snapshot snapshot;
	private static final long MIN_REFRESH_INTERVAL_MS = 60_000L;

	public SynologyAdapter() {
		super(ADAPTER_KIND);
	}

	public SynologyAdapter(String adapterDir, Integer adapterInstanceId) {
		super(ADAPTER_KIND, adapterDir, adapterInstanceId);
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
		SynologyConfig cfg = buildConfig(resourceConfig);
		this.config = cfg;
		this.httpClient = buildHttpClient(cfg);
		this.api = new SynologyApiClient(httpClient, cfg.username, cfg.password,
				componentLogger(SynologyApiClient.class));
		this.snapshot = null;

		logInfo("SynologyAdapter configured: host=" + cfg.host
				+ " port=" + cfg.port + " allowInsecure=" + cfg.allowInsecure);
	}

	private SynologyConfig buildConfig(ResourceConfig rc) {
		String host = getIdentifier(rc, "host");
		String port = getIdentifier(rc, "port");
		String allowInsecure = getIdentifier(rc, "allowInsecure");
		String username = getCredentialField(rc, "username");
		String password = getCredentialField(rc, "password");
		return new SynologyConfig(host, port, username, password, allowInsecure);
	}

	private ManagedHttpClient buildHttpClient(SynologyConfig cfg) {
		HttpClientBuilder b = HttpClientBuilder.builder()
				.baseUrl(cfg.baseUrl())
				.retryPolicy(RetryPolicy.builder()
						.maxAttempts(3)
						.baseDelayMs(1000)
						.build())
				.timeout(Duration.ofSeconds(30));
		if (cfg.allowInsecure) {
			// Lab opt-out: Synology DSM ships a self-signed cert by default.
			b.allowInsecure(true);
		} else {
			b.platformSsl(this);
		}
		return b.build();
	}

	// -----------------------------------------------------------------------
	// getTester — self-contained (controller calls it on a BARE instance:
	// configureAdapter has NOT run, so this.api / this.config are null). Derive
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
				SynologyApiClient testApi = new SynologyApiClient(
						testHttp, testCfg.username, testCfg.password,
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
	// getDiscoverer — enumerate the Synology resource tree
	// -----------------------------------------------------------------------

	@Override
	protected VcfCfDiscoverer<SynologyConfig> getDiscoverer() {
		return (cfg, http, param, dr) -> {
			logInfo("SynologyAdapter discover: starting resource enumeration");
			api.ensureSession();

			// World singleton
			dr.addResource(rcOf("SynologyWorld", "Synology World",
					"world_id", "synology_world"));

			// Diskstation singleton
			SimpleJson dsmInfo = api.dsmInfo();
			String serial = dsmInfo.data().get("serial").asString("unknown");
			String model = dsmInfo.data().get("model").asString("");
			String dsName = model.isEmpty() ? serial : model + " " + serial;
			dr.addResource(rcOf("SynologyDiskstation", dsName, "serial", serial));

			SimpleJson storage = api.storageLoadInfo();
			int pools = 0, volumes = 0, disks = 0, caches = 0, luns = 0, exports = 0;

			for (SimpleJson pool : storage.data().get("storagePools").asList()) {
				dr.addResource(rcOf("SynologyStoragePool", poolDisplayName(pool),
						"pool_id", pool.get("id").asString()));
				pools++;
			}
			for (SimpleJson vol : storage.data().get("volumes").asList()) {
				String volPath = vol.get("vol_path").asString();
				dr.addResource(rcOf("SynologyVolume", volumeDisplayName(vol),
						"volume_id", vol.get("volume_id").asString(volPath)));
				volumes++;
			}
			for (SimpleJson disk : storage.data().get("disks").asList()) {
				String diskId = disk.get("id").asString();
				dr.addResource(rcOf("SynologyDisk", disk.get("name").asString(diskId),
						"disk_id", diskId));
				disks++;
			}
			SimpleJson ssdCaches = storage.data().get("ssdCaches");
			if (!ssdCaches.isNull()) {
				for (SimpleJson cache : ssdCaches.asList()) {
					dr.addResource(rcOf("SynologySsdCache",
							cacheDisplayName(cache, storage),
							"cache_id", cache.get("id").asString()));
					caches++;
				}
			}

			SimpleJson lunList = api.iscsiLunList();
			for (SimpleJson lun : lunList.data().get("luns").asList()) {
				String uuid = lun.get("uuid").asString();
				dr.addResource(rcOf("SynologyIscsiLun",
						lun.get("name").asString(uuid), "lun_uuid", uuid));
				luns++;
			}

			SimpleJson shares = api.shareList();
			for (SimpleJson share : shares.data().get("shares").asList()) {
				String name = share.get("name").asString();
				try {
					SimpleJson rules = api.nfsSharePrivilege(name);
					if (rules.data().get("rule").size() > 0) {
						dr.addResource(rcOf("SynologyNfsExport", name,
								"share_name", name));
						exports++;
					}
				} catch (Exception e) {
					logWarn("Discover: NFS rule probe failed for share " + name
							+ ": " + e.getMessage());
				}
			}

			// UPS (optional)
			try {
				SimpleJson ups = api.upsGet();
				if (ups.data().get("usb_ups_connect").asBoolean()) {
					String upsModel = ups.data().get("model").asString("UPS");
					dr.addResource(rcOf("SynologyUps", upsModel,
							"ups_model", upsModel));
				}
			} catch (Exception e) {
				logInfo("Discover: UPS not available: " + e.getMessage());
			}

			logInfo("Synology discover: 1 world, 1 diskstation, " + pools
					+ " pools, " + volumes + " volumes, " + disks + " disks, "
					+ caches + " ssd-caches, " + luns + " luns, " + exports
					+ " nfs-exports");
		};
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

			@Override
			public ResourceStatusEnum mapCollectException(Exception e) {
				if (e instanceof java.net.ConnectException) {
					return ResourceStatusEnum.RESOURCE_STATUS_DOWN;
				}
				return ResourceStatusEnum.RESOURCE_STATUS_ERROR;
			}
		};
	}

	/**
	 * Return the snapshot for this cycle, refreshing it if it is null or older
	 * than {@link #MIN_REFRESH_INTERVAL_MS}. A refresh failure (session/REST
	 * error) propagates out so the framework marks the resource ERROR/DOWN —
	 * never a silent empty snapshot.
	 */
	private synchronized Snapshot currentSnapshot() throws Exception {
		Snapshot s = this.snapshot;
		long now = System.currentTimeMillis();
		if (s == null || (now - s.builtAt) >= MIN_REFRESH_INTERVAL_MS) {
			api.ensureSession();
			s = Snapshot.build(api, this);
			this.snapshot = s;
		}
		return s;
	}

	private void dispatchCollect(ResourceConfig rc, Snapshot snap,
			List<MetricData> out) {
		String kind = rc.getResourceKind();
		if (kind == null) return;
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
		metric(out, "Network|nic_count", (double) s.networkInterfaces.data().size());

		prop(out, "Fan|fan_status", fan.data().get("cool_fan").asString("unknown"));
		prop(out, "Fan|fan_speed_mode", fan.data().get("dual_fan_speed").asString("unknown"));

		prop(out, "NFS|nfs_enabled", nfs.data().get("enable_nfs").asBoolean() ? "true" : "false");
		prop(out, "NFS|nfs_v4_enabled", nfs.data().get("enable_nfs_v4").asBoolean() ? "true" : "false");

		SimpleJson nfsUtil = util.data().get("nfs");
		if (!nfsUtil.isNull() && nfsUtil.size() > 0) {
			SimpleJson nfsRow = nfsUtil.get(0);
			metric(out, "NFS|nfs_total_ops", nfsRow.get("total_OPS").asDouble());
			metric(out, "NFS|nfs_read_ops", nfsRow.get("read_OPS").asDouble());
			metric(out, "NFS|nfs_write_ops", nfsRow.get("write_OPS").asDouble());
			metric(out, "NFS|nfs_max_latency", nfsRow.get("total_max_latency").asDouble());
		}

		int nfsClients = 0;
		for (SimpleJson conn : s.connections.data().get("items").asList()) {
			if ("NFS".equals(conn.get("protocol").asString())) {
				nfsClients++;
			}
		}
		metric(out, "NFS|nfs_client_count", (double) nfsClients);
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

	private void collectIscsiLun(ResourceConfig rc, Snapshot s, List<MetricData> out) {
		String uuid = getIdentifier(rc, "lun_uuid");
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

		String iqn = s.targetIqnByName.get(name);
		prop(out, "Configuration|target_iqn", iqn != null ? iqn : "");
		String enabled = s.targetEnabledByName.get(name);
		prop(out, "Configuration|target_enabled", enabled != null ? enabled : "");
		String portals = s.targetPortalsByName.get(name);
		prop(out, "Configuration|network_portals", portals != null ? portals : "");

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

	private void collectNfsExport(ResourceConfig rc, Snapshot s, List<MetricData> out) {
		String name = getIdentifier(rc, "share_name");
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

		metric(out, "Clients|active_client_count",
				(double) s.nfsClientsByShare.getOrDefault(name, 0));
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
		SimpleJson storage = s.storage;

		String serial = s.dsmInfo.data().get("serial").asString("unknown");
		String dsModel = s.dsmInfo.data().get("model").asString("");
		String dsName = dsModel.isEmpty() ? serial : dsModel + " " + serial;

		ResourceKey world = rb.resource("SynologyWorld", "Synology World",
				"world_id", "synology_world");
		ResourceKey diskstation = rb.resource("SynologyDiskstation", dsName,
				"serial", serial);
		rb.parent(world, diskstation);

		// disk id -> human name
		Map<String, String> diskNames = new HashMap<>();
		for (SimpleJson disk : storage.data().get("disks").asList()) {
			diskNames.put(disk.get("id").asString(),
					disk.get("name").asString(disk.get("id").asString()));
		}

		for (SimpleJson pool : storage.data().get("storagePools").asList()) {
			String poolId = pool.get("id").asString();
			String poolPath = pool.get("pool_path").asString();
			ResourceKey poolKey = rb.resource("SynologyStoragePool",
					poolDisplayName(pool), "pool_id", poolId);
			rb.parent(diskstation, poolKey);

			for (SimpleJson vol : storage.data().get("volumes").asList()) {
				if (poolPath.equals(vol.get("pool_path").asString())) {
					String volId = vol.get("volume_id").asString(vol.get("vol_path").asString());
					rb.parent(poolKey, rb.resource("SynologyVolume",
							volumeDisplayName(vol), "volume_id", volId));
				}
			}

			SimpleJson poolDisks = pool.get("disks");
			if (!poolDisks.isNull()) {
				for (SimpleJson diskRef : poolDisks.asList()) {
					String diskId = diskRef.asString();
					if (diskId != null && !diskId.isEmpty()) {
						rb.parent(poolKey, rb.resource("SynologyDisk",
								diskNames.getOrDefault(diskId, diskId),
								"disk_id", diskId));
					}
				}
			}
			SimpleJson cacheDiskIds = pool.get("cache_disks");
			if (!cacheDiskIds.isNull()) {
				for (SimpleJson diskRef : cacheDiskIds.asList()) {
					String diskId = diskRef.asString();
					if (diskId != null && !diskId.isEmpty()) {
						rb.parent(poolKey, rb.resource("SynologyDisk",
								diskNames.getOrDefault(diskId, diskId),
								"disk_id", diskId));
					}
				}
			}
		}

		// iSCSI LUN -> child of Volume (joined by location == vol_path)
		for (SimpleJson lun : s.lunList.data().get("luns").asList()) {
			String uuid = lun.get("uuid").asString();
			String location = lun.get("location").asString();
			for (SimpleJson vol : storage.data().get("volumes").asList()) {
				if (location.equals(vol.get("vol_path").asString())) {
					String volId = vol.get("volume_id").asString(vol.get("vol_path").asString());
					ResourceKey volKey = rb.resource("SynologyVolume",
							volumeDisplayName(vol), "volume_id", volId);
					rb.parent(volKey, rb.resource("SynologyIscsiLun",
							lun.get("name").asString(uuid), "lun_uuid", uuid));
					break;
				}
			}
		}

		// NFS Export -> child of Volume (joined by vol_path)
		for (SimpleJson share : s.shares.data().get("shares").asList()) {
			String name = share.get("name").asString();
			SimpleJson rules = s.nfsRulesByShare.get(name);
			if (rules == null || rules.data().get("rule").size() == 0) continue;
			String volPath = share.get("vol_path").asString();
			for (SimpleJson vol : storage.data().get("volumes").asList()) {
				if (volPath.equals(vol.get("vol_path").asString())) {
					String volId = vol.get("volume_id").asString(vol.get("vol_path").asString());
					ResourceKey volKey = rb.resource("SynologyVolume",
							volumeDisplayName(vol), "volume_id", volId);
					rb.parent(volKey, rb.resource("SynologyNfsExport", name,
							"share_name", name));
					break;
				}
			}
		}

		// SSD Cache -> child of Volume; cache NVMe disks -> child of SSD Cache
		SimpleJson ssdCaches = storage.data().get("ssdCaches");
		if (!ssdCaches.isNull()) {
			for (SimpleJson cache : ssdCaches.asList()) {
				String cacheId = cache.get("id").asString();
				String mountSpaceId = cache.get("mountSpaceId").asString("");
				if (cacheId == null || cacheId.isEmpty() || mountSpaceId.isEmpty()) continue;
				ResourceKey cacheKey = rb.resource("SynologySsdCache",
						cacheDisplayName(cache, storage), "cache_id", cacheId);

				for (SimpleJson vol : storage.data().get("volumes").asList()) {
					if (mountSpaceId.equals(vol.get("id").asString(""))) {
						String volId = vol.get("volume_id").asString(vol.get("vol_path").asString());
						ResourceKey volKey = rb.resource("SynologyVolume",
								volumeDisplayName(vol), "volume_id", volId);
						rb.parent(volKey, cacheKey);
						break;
					}
				}

				SimpleJson cacheDisks = cache.get("disks");
				if (!cacheDisks.isNull()) {
					for (SimpleJson diskRef : cacheDisks.asList()) {
						String diskId = diskRef.asString();
						if (diskId != null && !diskId.isEmpty()) {
							rb.parent(cacheKey, rb.resource("SynologyDisk",
									diskNames.getOrDefault(diskId, diskId),
									"disk_id", diskId));
						}
					}
				}
			}
		}

		// UPS -> child of Diskstation
		if (s.ups != null && s.ups.data().get("usb_ups_connect").asBoolean()) {
			String upsModel = s.ups.data().get("model").asString("UPS");
			rb.parent(diskstation, rb.resource("SynologyUps", upsModel,
					"ups_model", upsModel));
		}

		logInfo("Relationships built: internal World>Diskstation>Pool>"
				+ "Volume>{LUN,NFS,SSDCache,Disk} tree (no foreign stitching)");
		return rb.build();
	}

	// -----------------------------------------------------------------------
	// Display name helpers
	// -----------------------------------------------------------------------

	private static String poolDisplayName(SimpleJson pool) {
		return "Storage Pool " + pool.get("num_id").asLong();
	}

	private static String volumeDisplayName(SimpleJson vol) {
		return "Volume " + vol.get("num_id").asLong();
	}

	private static String cacheDisplayName(SimpleJson cache, SimpleJson storage) {
		String mountSpaceId = cache.get("mountSpaceId").asString("");
		for (SimpleJson vol : storage.data().get("volumes").asList()) {
			if (mountSpaceId.equals(vol.get("id").asString(""))) {
				return "SSD Cache (Volume " + vol.get("num_id").asLong() + ")";
			}
		}
		return "SSD Cache";
	}

	// -----------------------------------------------------------------------
	// onDiscard
	// -----------------------------------------------------------------------

	@Override
	public void onDiscard() {
		SynologyApiClient a = this.api;
		if (a != null) a.logout();
		this.snapshot = null;
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
		final long builtAt = System.currentTimeMillis();

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
		SimpleJson ups; // nullable — UPS optional

		// Derived lookups
		final Map<String, SimpleJson> diskIoByDevice = new HashMap<>();
		final Map<String, SimpleJson> volIoByName = new HashMap<>();
		final Map<String, SimpleJson> lunIoByUuid = new HashMap<>();
		final Map<String, String> targetIqnByName = new HashMap<>();
		final Map<String, String> targetEnabledByName = new HashMap<>();
		final Map<String, String> targetPortalsByName = new HashMap<>();
		final Map<String, SimpleJson> nfsRulesByShare = new HashMap<>();
		final Map<String, Integer> nfsClientsByShare = new HashMap<>();

		static Snapshot build(SynologyApiClient api, SynologyAdapter adapter)
				throws Exception {
			Snapshot s = new Snapshot();
			s.dsmInfo = api.dsmInfo();
			s.systemInfo = api.systemInfo();
			s.utilization = api.utilization();
			s.fanSpeed = api.fanSpeed();
			s.nfsService = api.nfsServiceGet();
			s.storage = api.storageLoadInfo();
			s.lunList = api.iscsiLunList();
			s.targets = api.iscsiTargetList();
			s.networkInterfaces = api.networkInterfaceList();
			s.connections = api.currentConnections();
			s.shares = api.shareList();
			try {
				SimpleJson ups = api.upsGet();
				if (ups.data().get("usb_ups_connect").asBoolean()) {
					s.ups = ups;
				}
			} catch (Exception e) {
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
					+ (s.ups != null));
			if (diskCount == 0) {
				adapter.logWarn("Synology API snapshot: 0 disks returned from "
						+ "storage load_info — expected a populated DiskStation");
			}
			return s;
		}

		private void buildLookups(SynologyApiClient api, SynologyAdapter adapter) {
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
			// NFS rule probe per share (identifies a share as an NFS export);
			// a probe failure skips that share rather than faking an export.
			for (SimpleJson share : shares.data().get("shares").asList()) {
				String name = share.get("name").asString();
				try {
					SimpleJson rules = api.nfsSharePrivilege(name);
					if (rules.data().get("rule").size() > 0) {
						nfsRulesByShare.put(name, rules);
					}
				} catch (Exception e) {
					adapter.logWarn("Snapshot: NFS rule probe failed for share "
							+ name + ": " + e.getMessage());
				}
			}
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
				if (p.get("id").asString().equals(poolId)) return p;
			}
			return null;
		}

		SimpleJson findVolume(String volId) {
			for (SimpleJson v : storage.data().get("volumes").asList()) {
				String id = v.get("volume_id").asString(v.get("vol_path").asString());
				if (id.equals(volId)) return v;
			}
			return null;
		}

		SimpleJson findDisk(String diskId) {
			for (SimpleJson d : storage.data().get("disks").asList()) {
				if (d.get("id").asString().equals(diskId)) return d;
			}
			return null;
		}

		SimpleJson findCache(String cacheId) {
			SimpleJson caches = storage.data().get("ssdCaches");
			if (caches.isNull()) return null;
			for (SimpleJson c : caches.asList()) {
				if (c.get("id").asString().equals(cacheId)) return c;
			}
			return null;
		}

		SimpleJson findLun(String uuid) {
			for (SimpleJson l : lunList.data().get("luns").asList()) {
				if (l.get("uuid").asString().equals(uuid)) return l;
			}
			return null;
		}

		SimpleJson findShare(String name) {
			for (SimpleJson sh : shares.data().get("shares").asList()) {
				if (sh.get("name").asString().equals(name)) return sh;
			}
			return null;
		}
	}
}
