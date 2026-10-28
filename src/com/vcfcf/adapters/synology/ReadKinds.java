package com.vcfcf.adapters.synology;

import java.util.Collections;
import java.util.LinkedHashSet;
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
 *       {@code SynologyDisk}: together, only when {@code load_info} was read
 *       completely: {@code storagePools}, {@code volumes} and {@code disks}
 *       each a list, {@code disks} not empty (a DiskStation always has a
 *       disk; the snapshot already WARNs on 0), and every pool, volume and
 *       disk carrying its identity ({@code id}; a volume's
 *       {@code volume_id}, else its {@code vol_path}).</li>
 *   <li>{@code SynologySsdCache}: only when {@code ssdCaches} was a list and
 *       every cache carried its {@code id}.</li>
 *   <li>{@code SynologyIscsiLun}: only when {@code SYNO.Core.ISCSI.LUN} was
 *       read, its {@code luns} was a list and every LUN carried its
 *       {@code uuid}. A NAS without SAN Manager (DSM error 102, read as no
 *       LUNs) sends no list, so its LUNs are not counted.</li>
 *   <li>{@code SynologyNfsExport}: only when {@code SYNO.Core.Share} was read
 *       as a list with a name on every share, the NFS service was not
 *       reported off (an export is ERROR, not gone, while NFS is off), and no
 *       share's NFS rule probe failed ({@code nfsProbeFailed} empty). One
 *       failed probe leaves every export out: the hook is per kind, not per
 *       share.</li>
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
	 * @param disks         how many disks it holds
	 * @param withoutId     pools, volumes and disks with no identity
	 */
	void storage(boolean poolList, boolean volumeList, boolean diskList,
			int disks, int withoutId) {
		storageComplete = poolList && volumeList && diskList && disks > 0
				&& withoutId == 0;
	}

	/**
	 * Storage {@code load_info}'s SSD caches.
	 *
	 * @param cacheList {@code ssdCaches} is a list
	 * @param withoutId caches in it with no {@code id}
	 */
	void ssdCaches(boolean cacheList, int withoutId) {
		cachesRead = cacheList && withoutId == 0;
	}

	/**
	 * The iSCSI LUN list.
	 *
	 * @param endpointRead {@code SYNO.Core.ISCSI.LUN} was read this cycle
	 * @param lunList      its {@code luns} is a list
	 * @param withoutUuid  LUNs in it with no {@code uuid}
	 */
	void luns(boolean endpointRead, boolean lunList, int withoutUuid) {
		lunsRead = endpointRead && lunList && withoutUuid == 0;
	}

	/**
	 * The NFS exports: the share list and every share's rule probe.
	 *
	 * @param sharesRead   {@code SYNO.Core.Share} was read this cycle
	 * @param shareList    its {@code shares} is a list
	 * @param withoutName  shares in it with no {@code name}
	 * @param nfsOff       the NFS service reported itself off (no probe ran)
	 * @param probesFailed shares whose NFS rule probe failed this cycle
	 */
	void exports(boolean sharesRead, boolean shareList, int withoutName,
			boolean nfsOff, int probesFailed) {
		exportsRead = sharesRead && shareList && withoutName == 0 && !nfsOff
				&& probesFailed == 0;
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
		if (storageComplete) {
			out.add(POOL);
			out.add(VOLUME);
			out.add(DISK);
		}
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
