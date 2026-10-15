package com.vcfcf.adapters.synology;

import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
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
		T.check(SnapshotIsolation.volumeChildrenKnown(none, none),
				"everything read: Volumes are parents");
		T.check(!SnapshotIsolation.volumeChildrenKnown(
				Collections.singletonMap(SnapshotReads.LUNS, "x"), none),
				"LUN list unread: no Volume is a parent");
		T.check(!SnapshotIsolation.volumeChildrenKnown(
				Collections.singletonMap(SnapshotReads.SHARES, "x"), none),
				"share list unread: no Volume is a parent");
		T.check(SnapshotIsolation.volumeChildrenKnown(
				Collections.singletonMap(SnapshotReads.TARGETS, "x"), none),
				"target list unread: Volume children are still known");
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
		T.check(!SnapshotIsolation.volumeChildrenKnown(none, failed),
				"a failed probe counts like an unread share list: no Volume "
				+ "is a parent, so the export keeps its Volume edge");
	}

	static void reconfigureReleasesOnlyWhatWasReplaced() {
		Object a = new Object();
		Object b = new Object();
		T.eq(a, SnapshotIsolation.replaced(a, b), "replaced: released");
		T.eq(null, SnapshotIsolation.replaced(a, a), "still in use: kept");
		T.eq(null, SnapshotIsolation.replaced(null, b), "nothing before: nothing");
	}
}
