package com.vcfcf.adapters.synology;

import com.vcfcf.adapter.json.SimpleJson;
import com.vcfcf.adapter.stitch.ForeignResourceResolver;
import com.vcfcf.adapter.stitch.SuiteApiStitcher;

import com.integrien.alive.common.adapter3.Logger;
import com.integrien.alive.common.adapter3.ResourceKey;
import com.integrien.alive.common.adapter3.config.ResourceIdentifierConfig;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Synology → VMWARE Datastore cross-link resolver (build 16).
 *
 * <p><b>What it restores.</b> v1 emitted an <em>informational</em>
 * {@code Datastore → {iSCSI LUN, NFS Export}} cross-link so that, once a Synology
 * LUN/export is matched to the VMWARE Datastore backed by it, the Datastore's
 * existing {@code HostSystem}/{@code VirtualMachine} edges light up the storage
 * dependency graph for free. v1 resolved the Datastore by its path identity
 * ({@code DataStrorePath}) — never a MOID — via
 * {@code ForeignResourceResolver.loadAll("VMWARE","Datastore","DataStrorePath")}.
 * Build 14's v2 migration dropped the cross-link because the adapter had no
 * Suite API transport; build 16 wires it back through the framework
 * {@link SuiteApiStitcher} facade with the <em>same optional semantics</em> v1
 * had (v1's {@code stitchDatastores} was already gated on Suite API
 * availability).
 *
 * <p><b>Why a resolver, not phantom resources.</b> v1 minted bare
 * {@code VMWARE/Datastore} {@link ResourceKey}s from the computed path and made
 * the LUN/export their child — which fabricated a Datastore object when no
 * matching VMware datastore existed. Build 16 instead <em>resolves against real
 * inventory</em>: {@link #loadDatastores()} pulls the actual VMWARE Datastores
 * and {@link #matchByPath(String)} returns a {@link ResourceKey} only when the
 * computed path matches one that exists. Zero datastores loaded on a working
 * connection is legitimate (no VMware datastore is backed by this NAS) — never
 * a fabricated edge, never WARN spam.
 *
 * <p><b>Transport.</b> Foreign resources are listed through a
 * {@link ForeignResourceResolver.SuiteApiBridge} backed by the ambient
 * {@link SuiteApiStitcher#get(String)} — the same authenticated
 * {@code GET /api/resources} path the compliance adapter uses. The framework
 * does not compile against any Suite API artifact; this bridge is the boundary.
 */
public final class SynologyStitcher {

	private final ForeignResourceResolver.SuiteApiBridge bridge;
	private final Logger logger;

	/**
	 * Last {@link #loadDatastores()} result: every VMWARE Datastore
	 * {@link ResourceKey} that shares a given {@code DataStrorePath}, indexed by
	 * that path. <b>Multi-valued</b> (build 1.0.0.18): a shared datastore resolves
	 * to N {@code VMWARE/Datastore} objects — one per vCenter view, same NAA /
	 * server path but a distinct {@code (VMEntityObjectID, VMEntityVCID)} identity.
	 * {@code loadAll} (single-valued) silently collapsed those to the last-seen
	 * copy and dropped the rest, so only one vCenter's datastore got the cross-MP
	 * edge. We index the bridge entries ourselves to keep all copies.
	 */
	private Map<String, List<ResourceKey>> datastoresByPath = Collections.emptyMap();

	public SynologyStitcher(SuiteApiStitcher stitcher, Logger logger) {
		this.logger = logger;
		this.bridge = new SuiteApiDatastoreBridge(stitcher, logger);
	}

	/**
	 * Load all VMWARE Datastores from inventory, indexing <b>every</b> copy that
	 * carries a given {@code DataStrorePath} (a shared datastore appears once per
	 * vCenter view, same path, distinct identity). Returns the number of Datastore
	 * objects loaded. A Suite API failure yields an empty index (logged WARN) —
	 * never a thrown exception out of the relationship pass.
	 *
	 * <p><b>Why not {@code resolver.loadAll}.</b> {@code ForeignResourceResolver}
	 * indexes by {@code idValue} into a {@code Map<String,ResourceKey>}, so two
	 * datastores with the same {@code DataStrorePath} collapse to one — the fleet
	 * never sees the second vCenter's copy. We consume the same
	 * {@link ForeignResourceResolver.SuiteApiBridge} directly and build a
	 * multi-valued index, reusing the resolver's exact {@link ResourceKey}
	 * construction (name/kind/adapterKind + per-identifier uniqueness flags).
	 */
	public int loadDatastores() {
		Map<String, List<ResourceKey>> index = new HashMap<>();
		int total = 0;
		try {
			List<ForeignResourceResolver.ResourceEntry> entries =
					bridge.listResources("VMWARE", "Datastore");
			if (entries != null) {
				for (ForeignResourceResolver.ResourceEntry e : entries) {
					if (e == null) continue;
					total++;
					ResourceKey key = new ResourceKey(e.name, e.resourceKind,
							e.adapterKind);
					String pathVal = null;
					for (String[] id : e.identifiers) {
						if (id == null || id.length < 2) continue;
						boolean isUnique = id.length >= 3
								&& Boolean.parseBoolean(id[2]);
						key.addIdentifier(new ResourceIdentifierConfig(
								id[0], id[1], isUnique));
						if ("DataStrorePath".equals(id[0])) {
							pathVal = id[1];
						}
					}
					if (pathVal != null && !pathVal.isEmpty()) {
						index.computeIfAbsent(pathVal, k -> new ArrayList<>())
								.add(key);
					}
				}
			}
		} catch (Exception ex) {
			logger.warn("SynologyStitcher: VMWARE Datastore load failed: "
					+ ex.getMessage());
			index = Collections.emptyMap();
		}
		this.datastoresByPath = index;
		int keyCount = 0;
		for (List<ResourceKey> l : index.values()) keyCount += l.size();
		logger.info("SynologyStitcher: loaded " + total + " VMWARE Datastores, "
				+ index.size() + " distinct DataStrorePath values, " + keyCount
				+ " keys indexed");
		return total;
	}

	/**
	 * Resolve <b>all</b> VMWARE Datastore {@link ResourceKey}s sharing the given
	 * {@code DataStrorePath} value, or an empty list when none exist in inventory.
	 * One path can map to N datastores (one per vCenter view) — the caller emits a
	 * cross-MP edge from each.
	 */
	public List<ResourceKey> matchByPath(String dataStorePath) {
		if (dataStorePath == null || dataStorePath.isEmpty()) {
			return Collections.emptyList();
		}
		List<ResourceKey> matches = datastoresByPath.get(dataStorePath);
		return matches != null ? matches : Collections.emptyList();
	}

	/**
	 * Synology iSCSI LUN UUID → ESXi VMFS extent path key
	 * ({@code "VMFS:|naa....|"}). Reproduces v1's {@code synologyUuidToNaa}
	 * byte-for-byte: strip hyphens (re-joined with a {@code d} separator), prefix
	 * the Type-6 OUI {@code 6001405}, and truncate to a 25-char tail.
	 */
	public static String lunDataStorePath(String uuid) {
		if (uuid == null || uuid.isEmpty()) return null;
		String[] parts = uuid.split("-");
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < parts.length; i++) {
			if (i > 0) sb.append("d");
			sb.append(parts[i]);
		}
		String naa = "naa.6001405" + sb.substring(0, Math.min(25, sb.length()));
		return "VMFS:|" + naa + "|";
	}

	/**
	 * NFS export → ESXi datastore path key {@code "<nas_ip>/<vol_path>/<share>"}.
	 * Reproduces v1's NFS path construction: strip the leading slash from the
	 * volume path, append the share name. One key per supplied NAS IP (covers
	 * round-robin / multi-homed mounts — ESXi keys NFS datastores by the server
	 * address it actually mounted).
	 */
	public static String nfsDataStorePath(String nasIp, String volPath,
			String shareName) {
		if (nasIp == null || nasIp.isEmpty()) return null;
		String serverPath = (volPath != null && volPath.startsWith("/"))
				? volPath.substring(1) : (volPath == null ? "" : volPath);
		return nasIp + "/" + serverPath + "/" + shareName;
	}

	/**
	 * Extract the connected NAS IPs from a cached
	 * {@code SYNO.Core.Network.Interface list} response (the per-cycle
	 * {@code Snapshot.networkInterfaces} — no live call). Mirrors v1: an
	 * interface contributes its IP only when it carries a non-empty {@code ip}
	 * and {@code status == "connected"}.
	 */
	public static List<String> connectedNasIps(SimpleJson networkInterfaces) {
		List<String> ips = new ArrayList<>();
		if (networkInterfaces == null) return ips;
		SimpleJson data = networkInterfaces.data();
		if (data == null || data.isNull() || !data.isList()) return ips;
		for (SimpleJson nic : data.asList()) {
			String ip = nic.get("ip").asString("");
			String status = nic.get("status").asString("");
			if (!ip.isEmpty() && "connected".equals(status)) {
				ips.add(ip);
			}
		}
		return ips;
	}

	/**
	 * {@link ForeignResourceResolver.SuiteApiBridge} over the ambient Suite API.
	 * Lists VMWARE Datastores via {@code GET /api/resources} and maps each entry
	 * to a {@link ForeignResourceResolver.ResourceEntry} carrying its
	 * {@code DataStrorePath} identifier. Parse shape matches the compliance
	 * adapter's {@code fetchResources}.
	 */
	private static final class SuiteApiDatastoreBridge
			implements ForeignResourceResolver.SuiteApiBridge {

		private final SuiteApiStitcher stitcher;
		private final Logger logger;

		SuiteApiDatastoreBridge(SuiteApiStitcher stitcher, Logger logger) {
			this.stitcher = stitcher;
			this.logger = logger;
		}

		@Override
		public List<ForeignResourceResolver.ResourceEntry> listResources(
				String adapterKind, String resourceKind) throws Exception {
			List<ForeignResourceResolver.ResourceEntry> out = new ArrayList<>();
			String enc = java.net.URLEncoder.encode(resourceKind, "UTF-8");
			String body = stitcher.get("/api/resources?adapterKind="
					+ java.net.URLEncoder.encode(adapterKind, "UTF-8")
					+ "&resourceKind=" + enc + "&pageSize=10000");
			SimpleJson parsed = SimpleJson.parse(body);
			if (parsed == null || parsed.isNull()) return out;
			SimpleJson list = parsed.get("resourceList");
			if (list == null || !list.isList()) return out;
			for (SimpleJson r : list.asList()) {
				if (r == null || r.isNull()) continue;
				SimpleJson key = r.get("resourceKey");
				if (key == null || key.isNull()) continue;
				String name = key.get("name").asString(null);
				List<String[]> identifiers = new ArrayList<>();
				SimpleJson ids = key.get("resourceIdentifiers");
				if (ids != null && ids.isList()) {
					for (SimpleJson id : ids.asList()) {
						String idName = id.get("identifierType").get("name")
								.asString(null);
						String idVal = id.get("value").asString(null);
						if (idName == null) continue;
						// Propagate the real per-identifier uniqueness flag.
						// Hardcoding "true" built a 4-tuple identity that could
						// not bind to the real 2-tuple VMWARE Datastore key, so
						// the cross-MP edge was silently dropped. Absent/null
						// defaults to false (asBoolean) — never over-mark.
						boolean isUnique = id.get("identifierType")
								.get("isPartOfUniqueness").asBoolean();
						identifiers.add(new String[]{idName,
								idVal == null ? "" : idVal,
								isUnique ? "true" : "false"});
					}
				}
				out.add(new ForeignResourceResolver.ResourceEntry(
						adapterKind, resourceKind, name, identifiers));
			}
			return out;
		}
	}
}
