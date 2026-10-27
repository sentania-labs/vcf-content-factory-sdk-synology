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
		failedProbeFreezesOnlyItsVolume();
		twoNasesShareNoObject();
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

	/** Build 34 (issue #11): the fixture's DiskStation identity, its serial. */
	static final String SERIAL = "20B0RYRXRF3KF";

	/**
	 * An object of the fixture NAS: every kind but the DiskStation carries
	 * the DiskStation identity (build 34, issue #11).
	 */
	static SynologyTopology.Obj o(String kind, String name, String idKey, String id) {
		return new SynologyTopology.Obj(kind, name, idKey, id,
				DeviceIdentity.DISKSTATION_KIND.equals(kind) ? null : SERIAL);
	}

	static String edge(SynologyTopology.Obj p, SynologyTopology.Obj c) {
		return p.kind + ":" + p.id + "@" + p.device + "(" + p.name + ") -> "
				+ c.kind + ":" + c.id + "@" + c.device + "(" + c.name + ")";
	}

	/** A DS1520+ with one pool, one volume, a LUN, an NFS export, an SSD cache and a UPS. */
	static SynologyTopology.Input<Object> fullFixture() {
		SynologyTopology.Input<Object> in = new SynologyTopology.Input<>();
		in.dsmInfo = response(obj("serial", SERIAL, "model", "DS1520+"));
		in.device = new DeviceIdentity.Id(SERIAL, SERIAL, true);
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
		SynologyTopology.Obj world = o("SynologyWorld", "Synology World (" + SERIAL + ")", "world_id", "synology_world");
		SynologyTopology.Obj ds = o("SynologyDiskstation", "DS1520+ " + SERIAL, "serial", SERIAL);
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

		SynologyTopology.Obj ds = o("SynologyDiskstation", "DS1520+ " + SERIAL, "serial", SERIAL);
		SynologyTopology.Obj pool1 = o("SynologyStoragePool", "Storage Pool 1", "pool_id", "reuse_1");
		SynologyTopology.Obj pool2 = o("SynologyStoragePool", "Storage Pool 2", "pool_id", "reuse_2");
		SynologyTopology.Obj vol = o("SynologyVolume", "Volume 1", "volume_id", "/volume1");
		for (String e : Arrays.asList(
				edge(o("SynologyWorld", "Synology World (" + SERIAL + ")", "world_id", "synology_world"), ds),
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
			T.check(!e.startsWith("SynologyStoragePool:reuse_2@" + SERIAL
					+ "(Storage Pool 2) -> SynologyVolume"),
					"the pool without pool_path has no volume edge: " + e);
		}
		T.eq(0, warns.size(), "missing fields are expected shapes, not WARNs");
		T.eq(1, infos.size(), "one INFO line listing what was left out");
		String info = infos.get(0);
		T.check(info.contains("no pool_path") && info.contains("no location")
				&& info.contains("no id") && info.contains("no volume_id or vol_path"),
				"the INFO names each missing field: " + info);
	}

	/**
	 * Build 34 (issue #11): two DiskStations with the same DSM-local ids
	 * (reuse_1, /volume1, sata1, the UPS model, ...) emit no object in
	 * common, and every object but the DiskStation carries its NAS's
	 * identity; the fallback identity names the World and DiskStation by
	 * the endpoint.
	 */
	static void twoNasesShareNoObject() {
		SynologyTopology.Input<Object> a = fullFixture();
		SynologyTopology.Input<Object> b = fullFixture();
		b.dsmInfo = response(obj("model", "DS1520+"));
		b.device = new DeviceIdentity.Id("host:nas2.lab:5001", "nas2.lab:5001", false);
		java.util.Set<SynologyTopology.Obj> objsA = new HashSet<>();
		java.util.Set<SynologyTopology.Obj> objsB = new HashSet<>();
		SynologyTopology.build(MAPS, a, (p, c) -> { objsA.add(p); objsA.add(c); },
				x -> {}, x -> {});
		SynologyTopology.build(MAPS, b, (p, c) -> { objsB.add(p); objsB.add(c); },
				x -> {}, x -> {});
		T.eq(objsA.size(), objsB.size(), "same tree shape");
		for (SynologyTopology.Obj x : objsA) {
			T.check(!objsB.contains(x), "no shared object: " + x);
			if (x.kind.equals(DeviceIdentity.DISKSTATION_KIND)) {
				T.eq(null, x.device, "the DiskStation carries no second identifier");
				T.eq(SERIAL, x.id, "the DiskStation's serial identifier is the identity");
			} else {
				T.eq(SERIAL, x.device, "carries the identity: " + x);
			}
		}
		for (SynologyTopology.Obj x : objsB) {
			if (x.kind.equals(DeviceIdentity.DISKSTATION_KIND)) {
				T.eq("host:nas2.lab:5001", x.id, "fallback DiskStation identity");
				T.eq("DS1520+ nas2.lab:5001", x.name, "fallback DiskStation name");
			} else {
				T.eq("host:nas2.lab:5001", x.device, "carries the fallback: " + x);
			}
			if (x.kind.equals("SynologyWorld")) {
				T.eq("Synology World (nas2.lab:5001)", x.name, "one World per NAS");
			}
		}
	}

	/** Issue #14 interplay: an unread LUN or share list keeps Volume edges. */
	static void unreadListsKeepVolumeEdges() {
		SynologyTopology.Input<Object> in = fullFixture();
		in.volumeChildren = SnapshotIsolation.VolumeChildren.NONE;
		List<String> edges = run(in, new ArrayList<>(), new ArrayList<>());
		for (String e : edges) {
			T.check(!e.startsWith("SynologyVolume:"), "no Volume is a parent: " + e);
		}
		T.check(edges.contains(edge(
				o("SynologySsdCache", "SSD Cache (Volume 1)", "cache_id", "alloc_cache_1_1"),
				o("SynologyDisk", "Cache device 1", "disk_id", "nvme0n1"))),
				"the SSD cache still parents its disks");
	}

	/**
	 * Review NIT-R3: a share on Volume 1 whose NFS rule probe is always
	 * refused keeps Volume 1's edges unchanged, and Volume 2 still
	 * refreshes its LUN, NFS export and SSD cache edges.
	 */
	@SuppressWarnings("unchecked")
	static void failedProbeFreezesOnlyItsVolume() {
		SynologyTopology.Input<Object> in = fullFixture();
		Map<String, Object> st = (Map<String, Object>) ((Map<String, Object>) in.storage).get("data");
		((List<Object>) st.get("volumes")).add(obj("id", "volume_2", "num_id", 2,
				"vol_path", "/volume2", "pool_path", "reuse_1"));
		((List<Object>) st.get("ssdCaches")).add(obj("id", "alloc_cache_1_2",
				"mountSpaceId", "volume_2", "disks", list()));
		((List<Object>) ((Map<String, Object>) ((Map<String, Object>) in.lunList)
				.get("data")).get("luns")).add(obj("uuid", "e5f6", "name",
						"esx-lun-2", "location", "/volume2"));
		((List<Object>) ((Map<String, Object>) ((Map<String, Object>) in.shares)
				.get("data")).get("shares")).add(obj("name", "esx-nfs-2",
						"vol_path", "/volume2"));
		in.nfsExports = new HashSet<>(Arrays.asList("esx-nfs", "esx-nfs-2"));
		Map<String, String> volPathByShare = new LinkedHashMap<>();
		volPathByShare.put("esx-nfs", "/volume1");
		volPathByShare.put("smb-only", "/volume1");
		volPathByShare.put("esx-nfs-2", "/volume2");
		in.volumeChildren = SnapshotIsolation.volumeChildren(
				Collections.emptyMap(),
				Collections.singletonMap("smb-only", "code 105"), volPathByShare);

		List<String> edges = run(in, new ArrayList<>(), new ArrayList<>());
		for (String e : edges) {
			T.check(!e.startsWith("SynologyVolume:/volume1@"),
					"Volume 1 is not a parent, its edges are kept: " + e);
		}
		SynologyTopology.Obj vol2 = o("SynologyVolume", "Volume 2", "volume_id", "/volume2");
		for (String e : Arrays.asList(
				edge(vol2, o("SynologyIscsiLun", "esx-lun-2", "lun_uuid", "e5f6")),
				edge(vol2, o("SynologyNfsExport", "esx-nfs-2", "share_name", "esx-nfs-2")),
				edge(vol2, o("SynologySsdCache", "SSD Cache (Volume 2)", "cache_id",
						"alloc_cache_1_2")))) {
			T.check(edges.contains(e), "Volume 2 refreshes: " + e + " in " + edges);
		}
	}
}
