package com.vcfcf.adapters.synology;

import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Build 31 (issue #14, review W2): the adapter-side wiring of an optional
 * endpoint that was not read, through the SDK-free helper the adapter
 * delegates to. Run from the repo root (ci/run_java_tests.sh).
 */
public final class SnapshotIsolationTest {

	public static void main(String[] args) throws Exception {
		eachKindNeedsItsOwnList();
		unreadListMakesItsObjectsError();
		volumeChildrenFrozenWhileAListIsUnread();
		failedNfsProbeIsNotAnExportWithoutRules();
		failedNfsProbeFreezesOnlyItsVolume();
		reconfigureReleasesOnlyWhatWasReplaced();
		System.out.println("SnapshotIsolationTest: all assertions passed");
	}

	static void eachKindNeedsItsOwnList() {
		T.eq(SnapshotReads.LUNS, SnapshotIsolation.listFor("SynologyIscsiLun"),
				"a LUN is found in the LUN list");
		T.eq(SnapshotReads.SHARES,
				SnapshotIsolation.listFor("SynologyNfsExport"),
				"an NFS export is found in the share list");
		for (String k : new String[] {"SynologyWorld", "SynologyDiskstation",
				"SynologyStoragePool", "SynologyVolume", "SynologyDisk",
				"SynologySsdCache", "SynologyUps", null}) {
			T.eq(null, SnapshotIsolation.listFor(k),
					k + " needs no optional list");
		}
	}

	static void unreadListMakesItsObjectsError() throws Exception {
		Map<String, String> unread = new HashMap<>();
		unread.put(SnapshotReads.LUNS, "code 105");
		try {
			SnapshotIsolation.requireListRead(unread, "SynologyIscsiLun");
			throw new AssertionError("a LUN must be ERROR while the LUN list is unread");
		} catch (IOException e) {
			T.check(e.getMessage().contains(SnapshotReads.LUNS)
					&& e.getMessage().contains("code 105"),
					"the error names the list and the reason: " + e.getMessage());
		}
		// Only its own list matters.
		SnapshotIsolation.requireListRead(unread, "SynologyNfsExport");
		SnapshotIsolation.requireListRead(unread, "SynologyVolume");
		SnapshotIsolation.requireListRead(unread, "SynologyDiskstation");
		unread.put(SnapshotReads.SHARES, "code 105");
		try {
			SnapshotIsolation.requireListRead(unread, "SynologyNfsExport");
			throw new AssertionError("an export must be ERROR while the share list is unread");
		} catch (IOException expected) {
			// expected
		}
		// Unrelated optional endpoints never fail a LUN or export.
		Map<String, String> other = new HashMap<>();
		other.put(SnapshotReads.FAN, "x");
		other.put(SnapshotReads.TARGETS, "x");
		other.put(SnapshotReads.CONNECTIONS, "x");
		other.put(SnapshotReads.NICS, "x");
		other.put(SnapshotReads.NFS_SERVICE, "x");
		SnapshotIsolation.requireListRead(other, "SynologyIscsiLun");
		SnapshotIsolation.requireListRead(other, "SynologyNfsExport");
	}

	static void volumeChildrenFrozenWhileAListIsUnread() {
		Map<String, String> none = Collections.emptyMap();
		T.check(SnapshotIsolation.volumeChildren(none, none, none).allKnown(),
				"everything read: Volumes are parents");
		SnapshotIsolation.VolumeChildren luns = SnapshotIsolation.volumeChildren(
				Collections.singletonMap(SnapshotReads.LUNS, "x"), none, none);
		T.check(luns.noneKnown() && !luns.known("/volume1"),
				"LUN list unread: no Volume is a parent");
		T.check(SnapshotIsolation.volumeChildren(
				Collections.singletonMap(SnapshotReads.SHARES, "x"), none, none)
				.noneKnown(), "share list unread: no Volume is a parent");
		T.check(SnapshotIsolation.volumeChildren(
				Collections.singletonMap(SnapshotReads.TARGETS, "x"), none, none)
				.allKnown(), "target list unread: Volume children are still known");
	}

	/**
	 * Review B2: a share whose NFS rule probe failed is neither published
	 * as an export with zero rules nor dropped from its Volume.
	 */
	static void failedNfsProbeIsNotAnExportWithoutRules() throws Exception {
		Map<String, String> none = Collections.emptyMap();
		Map<String, String> failed = Collections.singletonMap("vmware",
				"SYNO.Core.FileServ.NFS.SharePrivilege load failed: code 105");
		try {
			SnapshotIsolation.requireNfsProbe(failed, "vmware");
			throw new AssertionError("an export whose rules were not read must be ERROR");
		} catch (IOException e) {
			T.check(e.getMessage().contains("vmware")
					&& e.getMessage().contains("code 105"),
					"the error names the share and the reason: " + e.getMessage());
		}
		SnapshotIsolation.requireNfsProbe(failed, "backups");
		SnapshotIsolation.requireNfsProbe(failed, null);
		SnapshotIsolation.requireNfsProbe(none, "vmware");
		SnapshotIsolation.VolumeChildren vc = SnapshotIsolation.volumeChildren(
				none, failed, Collections.singletonMap("vmware", "/volume1"));
		T.check(!vc.known("/volume1"), "a failed probe freezes its Volume, "
				+ "so the export keeps its Volume edge");
	}

	/**
	 * Review NIT-R3: one share whose probe is always refused froze every
	 * Volume's relationships. Now only the Volume it sits on.
	 */
	static void failedNfsProbeFreezesOnlyItsVolume() {
		Map<String, String> none = Collections.emptyMap();
		Map<String, String> volPathByShare = new LinkedHashMap<>();
		volPathByShare.put("vmware", "/volume1");
		volPathByShare.put("backups", "/volume2");
		volPathByShare.put("media", "/volume1");
		volPathByShare.put("orphan", null);
		Map<String, String> failed = Collections.singletonMap("vmware", "code 105");
		SnapshotIsolation.VolumeChildren vc =
				SnapshotIsolation.volumeChildren(none, failed, volPathByShare);
		T.check(!vc.known("/volume1"), "the failed share's Volume is frozen");
		T.check(vc.known("/volume2"), "another Volume refreshes");
		T.check(vc.known("/volume3"), "a Volume with no shares refreshes");
		T.check(vc.known(null), "a Volume without vol_path refreshes");
		T.check(!vc.allKnown() && !vc.noneKnown(), "some, not all, frozen");
		T.eq(Collections.singleton("/volume1"), vc.frozenVolumes(),
				"the frozen Volume is named for the log line");

		Map<String, String> two = new LinkedHashMap<>(failed);
		two.put("backups", "code 105");
		SnapshotIsolation.VolumeChildren both =
				SnapshotIsolation.volumeChildren(none, two, volPathByShare);
		T.check(!both.known("/volume1") && !both.known("/volume2")
				&& both.known("/volume3"), "two failed shares freeze their two Volumes");

		// A failed share whose vol_path is unknown could sit on any Volume.
		for (String share : new String[] {"orphan", "not-in-share-list"}) {
			T.check(SnapshotIsolation.volumeChildren(none,
					Collections.singletonMap(share, "x"), volPathByShare)
					.noneKnown(), share + ": vol_path unknown, every Volume frozen");
		}
		Map<String, String> blank = Collections.singletonMap("blank", "");
		T.check(SnapshotIsolation.volumeChildren(none,
				Collections.singletonMap("blank", "x"), blank).noneKnown(),
				"a blank vol_path is unknown too");
		// An unread list still freezes every Volume, whatever the probes say.
		T.check(SnapshotIsolation.volumeChildren(
				Collections.singletonMap(SnapshotReads.SHARES, "x"), failed,
				volPathByShare).noneKnown(), "unread share list: none known");
	}

	static void reconfigureReleasesOnlyWhatWasReplaced() {
		Object a = new Object();
		Object b = new Object();
		T.eq(a, SnapshotIsolation.replaced(a, b), "replaced: released");
		T.eq(null, SnapshotIsolation.replaced(a, a), "still in use: kept");
		T.eq(null, SnapshotIsolation.replaced(null, b), "nothing before: nothing");
	}
}
