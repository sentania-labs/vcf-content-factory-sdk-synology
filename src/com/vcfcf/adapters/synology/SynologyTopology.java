package com.vcfcf.adapters.synology;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Build 31 (issue #19): the internal World &gt; DiskStation &gt; Pool &gt;
 * Volume &gt; {LUN, NFS export, SSD cache, Disk} tree, joined from the DSM
 * snapshot. No VCF Ops SDK and no framework-jar dependency: generic over the
 * JSON node type through {@link Json} ({@code SimpleJson} in production, a
 * map in the plain-main tests), emitting edges to an {@link Edges} sink (the
 * adapter's {@code RelationshipBuilder}). The edge order, display names and
 * identifiers are exactly those of build 30's {@code buildRelationships}.
 *
 * <p>Before build 31 several joins called {@code equals} on a value that is
 * null when the field is missing: a pool without {@code pool_path}, a LUN
 * without {@code location}, a volume without {@code volume_id} and
 * {@code vol_path}. The NullPointerException aborted the whole build, the
 * caller caught it and published no relationships at all, every cycle. Now
 * every join compares null-safely ({@link #same}), an object without its
 * identifier or join key is left out of the one edge that needs it (one INFO
 * line per build lists them), and each pool, LUN, export and SSD cache is
 * joined in its own try block, so one odd object costs its own edges, not
 * the tree.
 */
final class SynologyTopology {

	private SynologyTopology() {}

	/** Read access to one JSON node type. A missing value is a null node. */
	interface Json<J> {
		/** The member {@code key} of an object node, or a null node. */
		J get(J node, String key);
		/** The node's string value, or {@code null} for a null node. */
		String str(J node);
		/** The node's integer value, 0 when not a number. */
		long num(J node);
		/** The node's boolean value, false when absent. */
		boolean bool(J node);
		/** The node's elements, empty when it is not a list. */
		List<J> list(J node);
		/** Whether the node is a null node. */
		boolean isNull(J node);
	}

	/** One object: resource kind, display name, identifier key and value. */
	static final class Obj {
		final String kind;
		final String name;
		final String idKey;
		final String id;

		Obj(String kind, String name, String idKey, String id) {
			this.kind = kind;
			this.name = name;
			this.idKey = idKey;
			this.id = id;
		}

		@Override
		public boolean equals(Object o) {
			if (!(o instanceof Obj)) return false;
			Obj x = (Obj) o;
			return kind.equals(x.kind) && Objects.equals(name, x.name)
					&& idKey.equals(x.idKey) && Objects.equals(id, x.id);
		}

		@Override
		public int hashCode() {
			return Objects.hash(kind, name, idKey, id);
		}

		@Override
		public String toString() {
			return kind + "[" + idKey + "=" + id + ", name=" + name + "]";
		}
	}

	/** Receives each parent to child edge, in emission order. */
	interface Edges {
		void parent(Obj parent, Obj child);
	}

	/** What the tree is joined from. */
	static final class Input<J> {
		/** {@code SYNO.DSM.Info} response. */
		J dsmInfo;
		/** {@code SYNO.Storage.CGI.Storage} load_info response. */
		J storage;
		/** {@code SYNO.Core.ISCSI.LUN} list response (empty when unread). */
		J lunList;
		/** {@code SYNO.Core.Share} list response (empty when unread). */
		J shares;
		/** {@code SYNO.Core.ExternalDevice.UPS} response, or null. */
		J ups;
		/** Share names that are NFS exports (more than zero NFS rules). */
		Set<String> nfsExports = Collections.emptySet();
		/**
		 * Which Volumes are parents this cycle (issue #14): none when the LUN
		 * or share list was not read, and (review NIT-R3) not the Volume a
		 * share whose NFS rule probe failed sits on. A Volume that is not a
		 * parent keeps its existing edges.
		 */
		SnapshotIsolation.VolumeChildren volumeChildren =
				SnapshotIsolation.VolumeChildren.ALL;
	}

	/** Null-safe join: true only when {@code a} is present and equal to {@code b}. */
	static boolean same(String a, String b) {
		return a != null && a.equals(b);
	}

	static <J> String poolName(Json<J> js, J pool) {
		return "Storage Pool " + js.num(js.get(pool, "num_id"));
	}

	static <J> String volumeName(Json<J> js, J vol) {
		return "Volume " + js.num(js.get(vol, "num_id"));
	}

	/** The volume identifier: {@code volume_id}, else {@code vol_path}, else null. */
	static <J> String volumeId(Json<J> js, J vol) {
		String id = js.str(js.get(vol, "volume_id"));
		return id != null ? id : js.str(js.get(vol, "vol_path"));
	}

	static <J> String cacheName(Json<J> js, J cache, J storage) {
		String mountSpaceId = orEmpty(js.str(js.get(cache, "mountSpaceId")));
		for (J vol : js.list(js.get(data(js, storage), "volumes"))) {
			if (mountSpaceId.equals(orEmpty(js.str(js.get(vol, "id"))))) {
				return "SSD Cache (Volume " + js.num(js.get(vol, "num_id")) + ")";
			}
		}
		return "SSD Cache";
	}

	/**
	 * Joins the tree and emits its edges.
	 *
	 * @param warn receives one line per object whose join threw unexpectedly
	 * @param info receives one line per build listing the objects left out of
	 *             an edge for a missing identifier or join key
	 * @return the number of objects left out of at least one edge
	 */
	static <J> int build(Json<J> js, Input<J> in, Edges out,
			Consumer<String> warn, Consumer<String> info) {
		List<String> skipped = new ArrayList<>();
		J dsm = data(js, in.dsmInfo);
		J st = data(js, in.storage);

		String serial = orDefault(js.str(js.get(dsm, "serial")), "unknown");
		String dsModel = orEmpty(js.str(js.get(dsm, "model")));
		String dsName = dsModel.isEmpty() ? serial : dsModel + " " + serial;
		Obj world = new Obj("SynologyWorld", "Synology World", "world_id",
				"synology_world");
		Obj diskstation = new Obj("SynologyDiskstation", dsName, "serial", serial);
		out.parent(world, diskstation);

		List<J> volumes = js.list(js.get(st, "volumes"));

		// disk id -> human name
		Map<String, String> diskNames = new HashMap<>();
		for (J disk : js.list(js.get(st, "disks"))) {
			String id = js.str(js.get(disk, "id"));
			if (id == null) continue;
			diskNames.put(id, orDefault(js.str(js.get(disk, "name")), id));
		}

		for (J pool : js.list(js.get(st, "storagePools"))) {
			try {
				String poolId = js.str(js.get(pool, "id"));
				if (poolId == null) {
					skipped.add("storage pool " + poolName(js, pool)
							+ " (no id): all its edges");
					continue;
				}
				String poolPath = js.str(js.get(pool, "pool_path"));
				Obj poolKey = new Obj("SynologyStoragePool", poolName(js, pool),
						"pool_id", poolId);
				out.parent(diskstation, poolKey);

				if (poolPath == null) {
					skipped.add("storage pool " + poolId
							+ " (no pool_path): its volume edges");
				}
				for (J vol : volumes) {
					if (same(poolPath, js.str(js.get(vol, "pool_path")))) {
						String volId = volumeId(js, vol);
						if (volId == null) {
							skipped.add(volumeName(js, vol)
									+ " (no volume_id or vol_path): its pool edge");
							continue;
						}
						out.parent(poolKey, new Obj("SynologyVolume",
								volumeName(js, vol), "volume_id", volId));
					}
				}
				diskEdges(js, js.get(pool, "disks"), poolKey, diskNames, out);
				diskEdges(js, js.get(pool, "cache_disks"), poolKey, diskNames, out);
			} catch (RuntimeException e) {
				warn.accept("Relationships: storage pool skipped, its edges are "
						+ "left out this cycle: " + SynologyFailures.describe(e));
			}
		}

		// iSCSI LUN -> child of Volume (joined by location == vol_path)
		if (!in.volumeChildren.noneKnown()) {
			for (J lun : js.list(js.get(data(js, in.lunList), "luns"))) {
				try {
					String uuid = js.str(js.get(lun, "uuid"));
					String location = js.str(js.get(lun, "location"));
					if (uuid == null || location == null) {
						skipped.add("iSCSI LUN " + orDefault(js.str(js.get(lun,
								"name")), String.valueOf(uuid)) + " (no "
								+ (uuid == null ? "uuid" : "location")
								+ "): its volume edge");
						continue;
					}
					for (J vol : volumes) {
						if (same(location, js.str(js.get(vol, "vol_path")))) {
							String volId = volumeId(js, vol);
							if (volId == null || !in.volumeChildren.known(location)) break;
							out.parent(new Obj("SynologyVolume",
									volumeName(js, vol), "volume_id", volId),
									new Obj("SynologyIscsiLun",
											orDefault(js.str(js.get(lun, "name")), uuid),
											"lun_uuid", uuid));
							break;
						}
					}
				} catch (RuntimeException e) {
					warn.accept("Relationships: iSCSI LUN skipped, its edge is "
							+ "left out this cycle: " + SynologyFailures.describe(e));
				}
			}

			// NFS Export -> child of Volume (joined by vol_path)
			for (J share : js.list(js.get(data(js, in.shares), "shares"))) {
				try {
					String name = js.str(js.get(share, "name"));
					if (name == null || !in.nfsExports.contains(name)) continue;
					String volPath = js.str(js.get(share, "vol_path"));
					if (volPath == null) {
						skipped.add("NFS export " + name + " (no vol_path): its "
								+ "volume edge");
						continue;
					}
					for (J vol : volumes) {
						if (same(volPath, js.str(js.get(vol, "vol_path")))) {
							String volId = volumeId(js, vol);
							if (volId == null || !in.volumeChildren.known(volPath)) break;
							out.parent(new Obj("SynologyVolume",
									volumeName(js, vol), "volume_id", volId),
									new Obj("SynologyNfsExport", name,
											"share_name", name));
							break;
						}
					}
				} catch (RuntimeException e) {
					warn.accept("Relationships: NFS export skipped, its edge is "
							+ "left out this cycle: " + SynologyFailures.describe(e));
				}
			}
		}

		// SSD Cache -> child of Volume; cache NVMe disks -> child of SSD Cache
		J ssdCaches = js.get(st, "ssdCaches");
		if (!js.isNull(ssdCaches)) {
			for (J cache : js.list(ssdCaches)) {
				try {
					String cacheId = js.str(js.get(cache, "id"));
					String mountSpaceId = orEmpty(js.str(js.get(cache, "mountSpaceId")));
					if (cacheId == null || cacheId.isEmpty() || mountSpaceId.isEmpty()) {
						continue;
					}
					Obj cacheKey = new Obj("SynologySsdCache",
							cacheName(js, cache, in.storage), "cache_id", cacheId);
					if (!in.volumeChildren.noneKnown()) {
						for (J vol : volumes) {
							if (mountSpaceId.equals(orEmpty(js.str(js.get(vol, "id"))))) {
								String volId = volumeId(js, vol);
								if (volId == null || !in.volumeChildren.known(
										js.str(js.get(vol, "vol_path")))) break;
								out.parent(new Obj("SynologyVolume",
										volumeName(js, vol), "volume_id", volId),
										cacheKey);
								break;
							}
						}
					}
					diskEdges(js, js.get(cache, "disks"), cacheKey, diskNames, out);
				} catch (RuntimeException e) {
					warn.accept("Relationships: SSD cache skipped, its edges are "
							+ "left out this cycle: " + SynologyFailures.describe(e));
				}
			}
		}

		// UPS -> child of Diskstation
		if (in.ups != null && js.bool(js.get(data(js, in.ups), "usb_ups_connect"))) {
			String upsModel = orDefault(js.str(js.get(data(js, in.ups), "model")), "UPS");
			out.parent(diskstation, new Obj("SynologyUps", upsModel, "ups_model",
					upsModel));
		}

		if (!skipped.isEmpty()) {
			info.accept("Relationships: " + skipped.size() + " object(s) left out "
					+ "of an edge for a missing identifier or join key: " + skipped);
		}
		return skipped.size();
	}

	private static <J> void diskEdges(Json<J> js, J ids, Obj parent,
			Map<String, String> diskNames, Edges out) {
		if (js.isNull(ids)) return;
		for (J ref : js.list(ids)) {
			String diskId = js.str(ref);
			if (diskId != null && !diskId.isEmpty()) {
				out.parent(parent, new Obj("SynologyDisk",
						diskNames.getOrDefault(diskId, diskId), "disk_id", diskId));
			}
		}
	}

	private static <J> J data(Json<J> js, J response) {
		return js.get(response, "data");
	}

	private static String orEmpty(String s) {
		return s != null ? s : "";
	}

	private static String orDefault(String s, String fallback) {
		return s != null ? s : fallback;
	}
}
