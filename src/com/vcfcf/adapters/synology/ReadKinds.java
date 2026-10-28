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
 * list leaves the kind out, so without it the last object of a kind is
 * removed by hand. A DSM error in a 200 ({@code success: false}) never
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
 *       disk; the snapshot already WARNs on 0), every pool, volume and
 *       disk carrying its identity ({@code id}; a volume's
 *       {@code volume_id}, else its {@code vol_path}), and
 *       {@link #noUnassembledPool}: {@code missing_pools} and
 *       {@code detected_pools} both sent as lists and both empty. DSM's own
 *       Storage Manager lists pools as {@code storagePools} plus those two
 *       ({@code PoolModule.loadPool}); a pool whose drives are absent (an
 *       expansion unit unplugged, drives pulled) or that awaits Online
 *       Assemble moves to them and leaves {@code storagePools}, and its
 *       volumes and disks may leave theirs. It is not gone, so while one is
 *       there, or either key is absent (no evidence), none of the three is
 *       counted (review R3-B1). A volume whose {@code pool_path} names a
 *       pool not in {@code storagePools} also leaves the pool kind out
 *       (review R3-W1), and so does a disk whose {@code used_by} names
 *       neither a listed pool nor a listed shared cache (review R4-W1).
 *       {@code overview_data.space_affect_by_eunit_lost} {@code true} or a
 *       non-empty {@code incompatible_eunit} (each only when DSM sent it;
 *       a value of the wrong type counts as affected) leaves all three out,
 *       as an unassembled pool does: a pool on a lost or unusable
 *       expansion unit is not gone (review R4-W1). An empty pool list
 *       also needs an empty volume list (a volume sits on a pool) and
 *       {@link #noPoolConfigured}: every disk states its {@code used_by}
 *       and none names anything but a listed shared cache. An empty volume
 *       list counts only with that same evidence of no pool; a pool with no
 *       volume on it is possible, and no DSM field seen so far says so.</li>
 *   <li>{@code SynologySsdCache}: only when {@code ssdCaches} was a list and
 *       every cache carried its {@code id}, no volume's {@code cache.id}
 *       names a cache missing from it and no {@code sharedCaches} entry is
 *       {@code is_missing: true} (review R3-W1); an empty list also needs
 *       {@link #noCacheConfigured}: {@code sharedCaches} an empty list, no
 *       volume with a {@code cache.id}, and every disk stating a
 *       {@code used_by} that is empty or a listed pool.</li>
 *   <li>SSD Cache, iSCSI LUN and NFS Export also need
 *       {@link #noUnassembledPool} and no affected expansion unit: each can
 *       live on an absent pool, so none is counted while a pool is missing
 *       or detected, while DSM did not say (review R3-B1), or while an
 *       expansion unit is lost or incompatible (review R4-W1).</li>
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
 *       share is an export, and the list is whole: the share list pages
 *       ({@code limit=2} answers 2 rows of {@code total} 20 on devel), so a
 *       {@code total} other than the rows returned leaves exports out
 *       (review R3-N1; an absent {@code total} is accepted, as DSM 7.3.2
 *       sends it). One failed probe leaves every export out: the
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
	private boolean storageListsComplete;
	private boolean poolsRead;
	private boolean volumesRead;
	private boolean cachesRead;
	private boolean lunsRead;
	private boolean exportsRead;
	private boolean upsRead;
	/** Review R3-B1: no missing or detected pool, and DSM said so. */
	private boolean noUnassembledPool;
	/** Review R3-W1: a volume's pool_path names an unlisted pool. */
	private boolean poolDangling;
	/** Review R4-W1: a disk's used_by names an unlisted pool. */
	private boolean diskPoolDangling;
	/** Review R4-W1: an expansion unit lost or incompatible. */
	private boolean expansionUnitAffected;
	/** Review R3-W1: an unlisted cache.id, or a shared cache is_missing. */
	private boolean cacheDangling;

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
		storageListsComplete = poolList && volumeList && diskList && disks > 0
				&& withoutId == 0;
		boolean noPoolProven = pools == 0 && volumes == 0 && noPool;
		poolsRead = storageListsComplete && (pools > 0 || noPoolProven);
		volumesRead = storageListsComplete && (volumes > 0 || noPoolProven);
	}

	/**
	 * Storage {@code load_info}'s {@code missing_pools} and
	 * {@code detected_pools} (review R3-B1).
	 *
	 * @param missingList  {@code missing_pools} is a list
	 * @param missing      how many it holds
	 * @param detectedList {@code detected_pools} is a list
	 * @param detected     how many it holds
	 */
	void unassembledPools(boolean missingList, int missing,
			boolean detectedList, int detected) {
		noUnassembledPool = noUnassembledPool(missingList, missing,
				detectedList, detected);
	}

	/**
	 * Positive evidence that no pool is missing or awaiting assembly: both
	 * keys sent as lists, both empty. An absent key is no evidence.
	 */
	static boolean noUnassembledPool(boolean missingList, int missing,
			boolean detectedList, int detected) {
		return missingList && detectedList && missing == 0 && detected == 0;
	}

	/**
	 * Pointers inside one load_info to objects its lists omit (review
	 * R3-W1): DSM still knows the object, so the list is not the inventory.
	 *
	 * @param volumePoolUnlisted  a volume's {@code pool_path} names a pool
	 *                            not in {@code storagePools}
	 * @param volumeCacheUnlisted a volume's {@code cache.id} names a cache
	 *                            not in {@code ssdCaches}
	 * @param sharedCacheMissing  a {@code sharedCaches} entry is
	 *                            {@code is_missing: true}
	 */
	void contradictions(boolean volumePoolUnlisted, boolean volumeCacheUnlisted,
			boolean sharedCacheMissing) {
		poolDangling = volumePoolUnlisted;
		cacheDangling = volumeCacheUnlisted || sharedCacheMissing;
	}

	/**
	 * Review R4-W1: some disk's {@code used_by} names neither a listed pool,
	 * a listed shared or SSD cache nor a hot spare
	 * ({@link StorageOverview#diskNamesUnlistedPool}).
	 * DSM still knows the pool, so the pool list is not the inventory.
	 */
	void disksNameUnlistedPool(boolean unlisted) {
		diskPoolDangling = unlisted;
	}

	/**
	 * Review R4-W1: {@code overview_data}'s expansion-unit evidence
	 * ({@link StorageOverview#eunitLost},
	 * {@link StorageOverview#eunitIncompatible}). Either one leaves out
	 * every kind that can live on a pool, as an unassembled pool does.
	 *
	 * @param lost         {@code space_affect_by_eunit_lost} affected
	 * @param incompatible {@code incompatible_eunit} affected
	 */
	void expansionUnits(boolean lost, boolean incompatible) {
		expansionUnitAffected = lost || incompatible;
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
	 * @param total        its {@code total}, or -1 when DSM sent none; any
	 *                     other value than {@code shares} is a partial page
	 *                     (R3-N1)
	 * @param withoutName  shares in it with no {@code name}
	 * @param nfsOff       the NFS service reported itself off (no probe ran)
	 * @param probesFailed shares whose NFS rule probe failed this cycle
	 * @param exports      shares delivered as exports; 0 is not proof
	 */
	void exports(boolean sharesRead, boolean shareList, int shares, int total,
			int withoutName, boolean nfsOff, int probesFailed, int exports) {
		exportsRead = sharesRead && shareList && shares > 0
				&& (total < 0 || total == shares) && withoutName == 0
				&& !nfsOff && probesFailed == 0 && exports > 0;
	}

	/**
	 * Positive evidence, from load_info's disks, that no storage pool
	 * exists: there is at least one disk, every disk states its
	 * {@code used_by} (null here when DSM sent none: no evidence), and each
	 * is empty (unused) or names a listed shared cache. A disk in a pool
	 * names the pool ({@code reuse_1} on devel), so a pool that DSM left out
	 * of {@code storagePools} still shows here. It also needs
	 * {@link #noUnassembledPool}: a pool whose drives are all absent leaves
	 * no disk to name it (review R3-B1).
	 *
	 * @param diskUsedBy        one entry per disk, its {@code used_by} or
	 *                          null
	 * @param sharedCacheIds    {@code sharedCaches[].id}, or null when
	 *                          {@code sharedCaches} was not a list
	 * @param noUnassembledPool {@link #noUnassembledPool} on this load_info
	 */
	static boolean noPoolConfigured(List<String> diskUsedBy,
			Collection<String> sharedCacheIds, boolean noUnassembledPool) {
		if (!noUnassembledPool) return false;
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
		// Review R3-B1: a missing or detected pool (or no word from DSM)
		// leaves out every kind that can live on it.
		// Review R4-W1: so does a lost or incompatible expansion unit.
		boolean everyPoolAccounted = noUnassembledPool && !expansionUnitAffected;
		boolean storageComplete = storageListsComplete && everyPoolAccounted;
		if (storageComplete && poolsRead && !poolDangling && !diskPoolDangling) {
			out.add(POOL);
		}
		if (storageComplete && volumesRead) out.add(VOLUME);
		if (storageComplete) out.add(DISK);
		if (everyPoolAccounted && cachesRead && !cacheDangling) out.add(CACHE);
		if (everyPoolAccounted && lunsRead) out.add(LUN);
		if (everyPoolAccounted && exportsRead) out.add(EXPORT);
		if (upsRead) out.add(UPS);
		return Collections.unmodifiableSet(out);
	}

	/** Whether an identity value is missing (null or blank). */
	static boolean noId(String id) {
		return id == null || id.isEmpty();
	}
}
