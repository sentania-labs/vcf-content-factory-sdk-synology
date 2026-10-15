package com.vcfcf.adapters.synology;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Build 31 (issue #19): the relationship tree joins null-safely and per
 * object. With a full DSM fixture it emits exactly build 30's edges in
 * build 30's order; with a pool missing pool_path, a pool missing id, a LUN
 * missing location and a volume missing its identifier it emits every
 * other edge and leaves out only the odd objects' own edges (build 30
 * threw a NullPointerException and published no relationships). DSM
 * payloads are modelled as maps and lists. Run from the repo root
 * (ci/run_java_tests.sh).
 */
public final class TopologyTest {

	/** {@link SynologyTopology.Json} over maps, lists and scalars. */
	static final SynologyTopology.Json<Object> MAPS = new SynologyTopology.Json<Object>() {
		@Override
		public Object get(Object node, String key) {
			return (node instanceof Map) ? ((Map<?, ?>) node).get(key) : null;
		}

		@Override
		public String str(Object node) {
			return node != null ? node.toString() : null;
		}

		@Override
		public long num(Object node) {
			if (node instanceof Number) return ((Number) node).longValue();
			try {
				return node != null ? Long.parseLong(node.toString()) : 0L;
			} catch (NumberFormatException e) {
				return 0L;
			}
		}

		@Override
		public boolean bool(Object node) {
			return Boolean.TRUE.equals(node);
		}

		@Override
		@SuppressWarnings("unchecked")
		public List<Object> list(Object node) {
			return (node instanceof List) ? (List<Object>) node
					: Collections.emptyList();
		}

		@Override
		public boolean isNull(Object node) {
			return node == null;
		}
	};

	public static void main(String[] args) {
		fullFixtureEmitsBuild30Edges();
		missingFieldsCostOnlyTheirOwnEdges();
		unreadListsKeepVolumeEdges();
		System.out.println("TopologyTest: all assertions passed");
	}

	/** Builds a map from alternating keys and values. */
	static Map<String, Object> obj(Object... kv) {
		Map<String, Object> m = new LinkedHashMap<>();
		for (int i = 0; i < kv.length; i += 2) {
			m.put((String) kv[i], kv[i + 1]);
		}
		return m;
	}

	static Map<String, Object> response(Object data) {
		return obj("data", data, "success", true);
	}

	static List<Object> list(Object... items) {
		return new ArrayList<>(Arrays.asList(items));
	}

	static SynologyTopology.Obj o(String kind, String name, String idKey, String id) {
		return new SynologyTopology.Obj(kind, name, idKey, id);
	}

	static String edge(SynologyTopology.Obj p, SynologyTopology.Obj c) {
		return p.kind + ":" + p.id + "(" + p.name + ") -> " + c.kind + ":" + c.id
				+ "(" + c.name + ")";
	}

	/** A DS1520+ with one pool, one volume, a LUN, an NFS export, an SSD cache and a UPS. */
	static SynologyTopology.Input<Object> fullFixture() {
		SynologyTopology.Input<Object> in = new SynologyTopology.Input<>();
		in.dsmInfo = response(obj("serial", "20B0RYRXRF3KF", "model", "DS1520+"));
		in.storage = response(obj(
				"disks", list(
						obj("id", "sata1", "name", "Drive 1"),
						obj("id", "sata2", "name", "Drive 2"),
						obj("id", "nvme0n1", "name", "Cache device 1")),
				"storagePools", list(obj("id", "reuse_1", "num_id", 1,
						"pool_path", "reuse_1", "disks", list("sata1", "sata2"),
						"cache_disks", list("nvme0n1"))),
				"volumes", list(obj("id", "volume_1", "num_id", 1,
						"vol_path", "/volume1", "pool_path", "reuse_1")),
				"ssdCaches", list(obj("id", "alloc_cache_1_1",
						"mountSpaceId", "volume_1", "disks", list("nvme0n1")))));
		in.lunList = response(obj("luns", list(obj("uuid", "a1b2", "name",
				"esx-lun", "location", "/volume1"))));
		in.shares = response(obj("shares", list(
				obj("name", "esx-nfs", "vol_path", "/volume1"),
				obj("name", "smb-only", "vol_path", "/volume1"))));
		in.ups = response(obj("usb_ups_connect", true, "model", "Back-UPS"));
		in.nfsExports = new HashSet<>(Arrays.asList("esx-nfs"));
		return in;
	}

	static List<String> run(SynologyTopology.Input<Object> in, List<String> warns,
			List<String> infos) {
		List<String> edges = new ArrayList<>();
		SynologyTopology.build(MAPS, in, (p, c) -> edges.add(edge(p, c)),
				warns::add, infos::add);
		return edges;
	}

	/** Must not regress: the edge list build 30's buildRelationships emits. */
	static void fullFixtureEmitsBuild30Edges() {
		SynologyTopology.Obj world = o("SynologyWorld", "Synology World", "world_id", "synology_world");
		SynologyTopology.Obj ds = o("SynologyDiskstation", "DS1520+ 20B0RYRXRF3KF", "serial", "20B0RYRXRF3KF");
		SynologyTopology.Obj pool = o("SynologyStoragePool", "Storage Pool 1", "pool_id", "reuse_1");
		SynologyTopology.Obj vol = o("SynologyVolume", "Volume 1", "volume_id", "/volume1");
		SynologyTopology.Obj cache = o("SynologySsdCache", "SSD Cache (Volume 1)", "cache_id", "alloc_cache_1_1");
		SynologyTopology.Obj nvme = o("SynologyDisk", "Cache device 1", "disk_id", "nvme0n1");
		List<String> want = Arrays.asList(
				edge(world, ds),
				edge(ds, pool),
				edge(pool, vol),
				edge(pool, o("SynologyDisk", "Drive 1", "disk_id", "sata1")),
				edge(pool, o("SynologyDisk", "Drive 2", "disk_id", "sata2")),
				edge(pool, nvme),
				edge(vol, o("SynologyIscsiLun", "esx-lun", "lun_uuid", "a1b2")),
				edge(vol, o("SynologyNfsExport", "esx-nfs", "share_name", "esx-nfs")),
				edge(vol, cache),
				edge(cache, nvme),
				edge(ds, o("SynologyUps", "Back-UPS", "ups_model", "Back-UPS")));
		List<String> warns = new ArrayList<>();
		List<String> infos = new ArrayList<>();
		T.eq(want, run(fullFixture(), warns, infos), "build 30 edges, in order");
		T.eq(0, warns.size(), "no WARN");
		T.eq(0, infos.size(), "nothing left out");
	}

	/** The issue's fixture: missing pool_path, location and id. */
	@SuppressWarnings("unchecked")
	static void missingFieldsCostOnlyTheirOwnEdges() {
		SynologyTopology.Input<Object> in = fullFixture();
		Map<String, Object> st = (Map<String, Object>) ((Map<String, Object>) in.storage).get("data");
		List<Object> pools = (List<Object>) st.get("storagePools");
		// A pool with no pool_path: it and its disks stay, its volume edges go.
		pools.add(obj("id", "reuse_2", "num_id", 2, "disks", list("sata3")));
		// A pool with no id: no identity, all its edges go.
		pools.add(obj("num_id", 3, "pool_path", "reuse_1", "disks", list("sata4")));
		List<Object> vols = (List<Object>) st.get("volumes");
		// A volume with neither volume_id nor vol_path: no identity.
		vols.add(obj("id", "volume_9", "num_id", 9, "pool_path", "reuse_1"));
		List<Object> luns = (List<Object>) ((Map<String, Object>) ((Map<String, Object>) in.lunList).get("data")).get("luns");
		// A LUN with no location (file-based or thin LUN on some DSM versions).
		luns.add(0, obj("uuid", "c3d4", "name", "thin-lun"));

		List<String> warns = new ArrayList<>();
		List<String> infos = new ArrayList<>();
		List<String> edges = run(in, warns, infos);

		SynologyTopology.Obj ds = o("SynologyDiskstation", "DS1520+ 20B0RYRXRF3KF", "serial", "20B0RYRXRF3KF");
		SynologyTopology.Obj pool1 = o("SynologyStoragePool", "Storage Pool 1", "pool_id", "reuse_1");
		SynologyTopology.Obj pool2 = o("SynologyStoragePool", "Storage Pool 2", "pool_id", "reuse_2");
		SynologyTopology.Obj vol = o("SynologyVolume", "Volume 1", "volume_id", "/volume1");
		for (String e : Arrays.asList(
				edge(o("SynologyWorld", "Synology World", "world_id", "synology_world"), ds),
				edge(ds, pool1),
				edge(pool1, vol),
				edge(ds, pool2),
				edge(pool2, o("SynologyDisk", "sata3", "disk_id", "sata3")),
				edge(vol, o("SynologyIscsiLun", "esx-lun", "lun_uuid", "a1b2")),
				edge(vol, o("SynologyNfsExport", "esx-nfs", "share_name", "esx-nfs")),
				edge(ds, o("SynologyUps", "Back-UPS", "ups_model", "Back-UPS")))) {
			T.check(edges.contains(e), "kept: " + e + " in " + edges);
		}
		for (String e : edges) {
			T.check(!e.contains("c3d4"), "the LUN without location has no edge: " + e);
			T.check(!e.contains("Storage Pool 3"), "the pool without id has no edge: " + e);
			T.check(!e.contains("Volume 9"), "the volume without identity has no edge: " + e);
			T.check(!e.startsWith("SynologyStoragePool:reuse_2(Storage Pool 2) -> SynologyVolume"),
					"the pool without pool_path has no volume edge: " + e);
		}
		T.eq(0, warns.size(), "missing fields are expected shapes, not WARNs");
		T.eq(1, infos.size(), "one INFO line listing what was left out");
		String info = infos.get(0);
		T.check(info.contains("no pool_path") && info.contains("no location")
				&& info.contains("no id") && info.contains("no volume_id or vol_path"),
				"the INFO names each missing field: " + info);
	}

	/** Issue #14 interplay: an unread LUN or share list keeps Volume edges. */
	static void unreadListsKeepVolumeEdges() {
		SynologyTopology.Input<Object> in = fullFixture();
		in.volumeChildrenKnown = false;
		List<String> edges = run(in, new ArrayList<>(), new ArrayList<>());
		for (String e : edges) {
			T.check(!e.startsWith("SynologyVolume:"), "no Volume is a parent: " + e);
		}
		T.check(edges.contains(edge(
				o("SynologySsdCache", "SSD Cache (Volume 1)", "cache_id", "alloc_cache_1_1"),
				o("SynologyDisk", "Cache device 1", "disk_id", "nvme0n1"))),
				"the SSD cache still parents its disks");
	}
}
