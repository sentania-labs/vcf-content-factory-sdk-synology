package com.vcfcf.adapters.synology;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Build 35 (framework buildkit 1.0.22): which resource kinds one enumeration
 * read in full, for the framework's {@code kindsReadThisCycle()}. The
 * framework counts a miss, and after 12 consecutive misses marks the object
 * NOT_EXISTING, only for a kind named here, so a kind is named only when
 * nothing about its read failed this cycle. That a kind was delivered is not
 * enough: a share whose NFS rule probe failed still leaves the exports of
 * the other shares. A false mark is not undone (the platform deletes the
 * object, with its history, after the Deleted Objects retention), a missed
 * one only leaves a stale object, so every doubt leaves the kind out. No VCF
 * Ops SDK and no framework-jar dependency, so the plain-main tests drive the
 * exact rule the adapter runs.
 *
 * <p><b>An empty answer is not proof that everything is gone</b> (build 35
 * review, the UniFi build 18 round 2 finding applied here). An empty list
 * declares its kind read only on positive evidence from another field DSM
 * provides that the kind really is empty; without such evidence an empty
 * list leaves the kind out, so the last object of a kind is never removed
 * automatically. A DSM error in a 200 ({@code success: false}) never
 * reaches here: {@code SynologyApiClient.call} throws on it, which fails the
 * build for a required endpoint and leaves an optional one unread.
 *
 * <p>Every rule below applies only in a cycle whose snapshot was built: a
 * failed required endpoint ({@code SYNO.DSM.Info}, {@code SYNO.Core.System},
 * utilization, storage {@code load_info}) or a failed identity throws out of
 * the enumeration, and the framework counts nothing then. The rule, per
 * kind:
 * <ul>
 *   <li>{@code SynologyDiskstation}: when {@code SYNO.Core.System.SystemHealth}
 *       was read too. The DiskStation's key comes from {@code SYNO.DSM.Info};
 *       SystemHealth, its one health read, has no kind of its own, so its
 *       failure leaves the DiskStation out.</li>
 *   <li>{@code SynologyStoragePool}, {@code SynologyVolume},
 *       {@code SynologyDisk}: only when {@code load_info} was read
 *       completely: {@code storagePools}, {@code volumes} and {@code disks}
 *       each a list, {@code disks} not empty (a DiskStation always has a
 *       disk; the snapshot already WARNs on 0), and every pool, volume and
 *       disk carrying its identity ({@code id}; a volume's
 *       {@code volume_id}, else its {@code vol_path}). An empty pool list
 *       also needs an empty volume list (a volume sits on a pool) and
 *       {@link #noPoolConfigured}: every disk states its {@code used_by}
 *       and none names anything but a listed shared cache. An empty volume
 *       list counts only with that same evidence of no pool; a pool with no
 *       volume on it is possible, and no DSM field seen so far says so.</li>
 *   <li>{@code SynologySsdCache}: only when {@code ssdCaches} was a list and
 *       every cache carried its {@code id}; an empty list also needs
 *       {@link #noCacheConfigured}: {@code sharedCaches} an empty list, no
 *       volume with a {@code cache.id}, and every disk stating a
 *       {@code used_by} that is empty or a listed pool.</li>
 *   <li>{@code SynologyIscsiLun}: only when {@code SYNO.Core.ISCSI.LUN} was
 *       read, its {@code luns} was a non-empty list and every LUN carried its
 *       {@code uuid}. A NAS without SAN Manager (DSM error 102, read as no
 *       LUNs) sends no list, so its LUNs are not counted. No DSM field
 *       says a NAS has no LUN, so an empty list counts nothing.</li>
 *   <li>{@code SynologyNfsExport}: only when {@code SYNO.Core.Share} was read
 *       as a non-empty list with a name on every share, the NFS service was
 *       not reported off (an export is ERROR, not gone, while NFS is off),
 *       no share's NFS rule probe failed ({@code nfsProbeFailed} empty; a
 *       probe answer with no rule list is a failed probe) and at least one
 *       share is an export. One failed probe leaves every export out: the
 *       hook is per kind, not per share. Zero exports is every probe
 *       answering "no rules", which is not proof either.</li>
 *   <li>{@code SynologyUps}: only when {@code SYNO.Core.ExternalDevice.UPS}
 *       was read and sent {@code usb_ups_connect}, and, when that reported a
 *       UPS connected, sent its {@code model} (the UPS's identity; without
 *       it the enumeration keys the UPS "UPS"). A read that reports no UPS
 *       connected counts the old UPS as gone.</li>
 * </ul>
 * {@code SynologyWorld} is never named and is excluded ({@link #EXCLUDED}):
 * it is the synthetic root, one per DiskStation, delivered every cycle.
 */
final class ReadKinds {

	static final String WORLD = "SynologyWorld";
	static final String DISKSTATION = DeviceIdentity.DISKSTATION_KIND;
	static final String POOL = "SynologyStoragePool";
	static final String VOLUME = "SynologyVolume";
	static final String DISK = "SynologyDisk";
	static final String CACHE = "SynologySsdCache";
	static final String LUN = "SynologyIscsiLun";
	static final String EXPORT = "SynologyNfsExport";
	static final String UPS = "SynologyUps";

	/** Kinds the framework must never mark. */
	static final Set<String> EXCLUDED =
			Collections.unmodifiableSet(new LinkedHashSet<>(
					Collections.singletonList(WORLD)));

	private boolean systemHealthRead;
	private boolean storageComplete;
	private boolean poolsRead;
	private boolean volumesRead;
	private boolean cachesRead;
	private boolean lunsRead;
	private boolean exportsRead;
	private boolean upsRead;

	/** {@code SYNO.Core.System.SystemHealth} was read this cycle. */
	void systemHealth(boolean read) {
		systemHealthRead = read;
	}

	/**
	 * Storage {@code load_info}'s pools, volumes and disks.
	 *
	 * @param poolList      {@code storagePools} is a list
	 * @param volumeList    {@code volumes} is a list
	 * @param diskList      {@code disks} is a list
	 * @param pools         how many pools it holds
	 * @param volumes       how many volumes it holds
	 * @param disks         how many disks it holds
	 * @param withoutId     pools, volumes and disks with no identity
	 * @param noPool        {@link #noPoolConfigured} on this load_info
	 */
	void storage(boolean poolList, boolean volumeList, boolean diskList,
			int pools, int volumes, int disks, int withoutId, boolean noPool) {
		storageComplete = poolList && volumeList && diskList && disks > 0
				&& withoutId == 0;
		boolean noPoolProven = pools == 0 && volumes == 0 && noPool;
		poolsRead = storageComplete && (pools > 0 || noPoolProven);
		volumesRead = storageComplete && (volumes > 0 || noPoolProven);
	}

	/**
	 * Storage {@code load_info}'s SSD caches.
	 *
	 * @param cacheList {@code ssdCaches} is a list
	 * @param caches    how many caches it holds
	 * @param withoutId caches in it with no {@code id}
	 * @param noCache   {@link #noCacheConfigured} on this load_info
	 */
	void ssdCaches(boolean cacheList, int caches, int withoutId,
			boolean noCache) {
		cachesRead = cacheList && withoutId == 0 && (caches > 0 || noCache);
	}

	/**
	 * The iSCSI LUN list.
	 *
	 * @param endpointRead {@code SYNO.Core.ISCSI.LUN} was read this cycle
	 * @param lunList      its {@code luns} is a list
	 * @param luns         how many LUNs it holds; 0 is not proof
	 * @param withoutUuid  LUNs in it with no {@code uuid}
	 */
	void luns(boolean endpointRead, boolean lunList, int luns,
			int withoutUuid) {
		lunsRead = endpointRead && lunList && luns > 0 && withoutUuid == 0;
	}

	/**
	 * The NFS exports: the share list and every share's rule probe.
	 *
	 * @param sharesRead   {@code SYNO.Core.Share} was read this cycle
	 * @param shareList    its {@code shares} is a list
	 * @param shares       how many shares it holds; 0 is not proof
	 * @param withoutName  shares in it with no {@code name}
	 * @param nfsOff       the NFS service reported itself off (no probe ran)
	 * @param probesFailed shares whose NFS rule probe failed this cycle
	 * @param exports      shares delivered as exports; 0 is not proof
	 */
	void exports(boolean sharesRead, boolean shareList, int shares,
			int withoutName, boolean nfsOff, int probesFailed, int exports) {
		exportsRead = sharesRead && shareList && shares > 0 && withoutName == 0
				&& !nfsOff && probesFailed == 0 && exports > 0;
	}

	/**
	 * Positive evidence, from load_info's disks, that no storage pool
	 * exists: there is at least one disk, every disk states its
	 * {@code used_by} (null here when DSM sent none: no evidence), and each
	 * is empty (unused) or names a listed shared cache. A disk in a pool
	 * names the pool ({@code reuse_1} on devel), so a pool that DSM left out
	 * of {@code storagePools} still shows here.
	 *
	 * @param diskUsedBy     one entry per disk, its {@code used_by} or null
	 * @param sharedCacheIds {@code sharedCaches[].id}, or null when
	 *                       {@code sharedCaches} was not a list
	 */
	static boolean noPoolConfigured(List<String> diskUsedBy,
			Collection<String> sharedCacheIds) {
		if (diskUsedBy == null || diskUsedBy.isEmpty()) return false;
		for (String u : diskUsedBy) {
			if (u == null) return false;
			if (u.isEmpty()) continue;
			if (sharedCacheIds == null || !sharedCacheIds.contains(u)) return false;
		}
		return true;
	}

	/**
	 * Positive evidence, from the rest of load_info, that no SSD cache is
	 * configured: {@code sharedCaches} is a list and empty (no cache group
	 * to allocate a cache from), no volume carries a {@code cache.id}, and
	 * there is at least one disk and every disk states a {@code used_by}
	 * that is empty or a listed pool (a cache disk names its cache group,
	 * {@code shared_cache_1} on devel).
	 *
	 * @param sharedCacheIds {@code sharedCaches[].id}, or null when not a list
	 * @param diskUsedBy     one entry per disk, its {@code used_by} or null
	 * @param poolIds        {@code storagePools[].id}
	 * @param volumeCached   some volume carries a non-empty {@code cache.id}
	 */
	static boolean noCacheConfigured(Collection<String> sharedCacheIds,
			List<String> diskUsedBy, Collection<String> poolIds,
			boolean volumeCached) {
		if (sharedCacheIds == null || !sharedCacheIds.isEmpty()) return false;
		if (volumeCached) return false;
		if (diskUsedBy == null || diskUsedBy.isEmpty()) return false;
		for (String u : diskUsedBy) {
			if (u == null) return false;
			if (u.isEmpty()) continue;
			if (poolIds == null || !poolIds.contains(u)) return false;
		}
		return true;
	}

	/**
	 * The UPS read.
	 *
	 * @param read         {@code SYNO.Core.ExternalDevice.UPS} answered
	 * @param connectKnown it sent {@code usb_ups_connect}
	 * @param connected    it reported a UPS connected
	 * @param hasModel     it sent the UPS's {@code model}
	 */
	void ups(boolean read, boolean connectKnown, boolean connected,
			boolean hasModel) {
		upsRead = read && connectKnown && (!connected || hasModel);
	}

	/** The kinds read in full, for {@code kindsReadThisCycle()}. */
	Set<String> kinds() {
		Set<String> out = new LinkedHashSet<>();
		if (systemHealthRead) out.add(DISKSTATION);
		if (poolsRead) out.add(POOL);
		if (volumesRead) out.add(VOLUME);
		if (storageComplete) out.add(DISK);
		if (cachesRead) out.add(CACHE);
		if (lunsRead) out.add(LUN);
		if (exportsRead) out.add(EXPORT);
		if (upsRead) out.add(UPS);
		return Collections.unmodifiableSet(out);
	}

	/** Whether an identity value is missing (null or blank). */
	static boolean noId(String id) {
		return id == null || id.isEmpty();
	}
}
