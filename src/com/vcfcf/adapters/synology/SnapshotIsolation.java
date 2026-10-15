package com.vcfcf.adapters.synology;

import java.io.IOException;
import java.util.Map;

/**
 * Build 31 (issue #14, review W2): the adapter-side decisions that keep an
 * optional DSM endpoint that was not read this cycle from publishing
 * defaults or dropping edges, and the reconfigure release selection (issue
 * #21). No VCF Ops SDK and no framework-jar dependency, so the plain-main
 * tests drive exactly the logic the adapter runs; {@code SynologyAdapter}
 * and its {@code Snapshot} delegate here.
 *
 * <p>{@code unread} maps an optional endpoint to a one-line reason (from
 * {@link SnapshotReads.Result#unread}); empty when every endpoint was read.
 */
final class SnapshotIsolation {

	private SnapshotIsolation() {}

	/**
	 * The optional list endpoint a resource kind's own collect depends on,
	 * or {@code null} when the kind is collected from the required endpoints
	 * only. An iSCSI LUN is found in the LUN list and an NFS export in the
	 * share list, so while that list is unread the object cannot be told
	 * apart from one that is gone.
	 */
	static String listFor(String kind) {
		if ("SynologyIscsiLun".equals(kind)) return SnapshotReads.LUNS;
		if ("SynologyNfsExport".equals(kind)) return SnapshotReads.SHARES;
		return null;
	}

	/**
	 * Throws when {@code kind} depends on a list that was not read this
	 * cycle, so the resource goes to ERROR instead of collecting nothing
	 * (or, worse, logging "absent from snapshot" for an object that is
	 * still there). A no-op for every other kind.
	 */
	static void requireListRead(Map<String, String> unread, String kind)
			throws IOException {
		String endpoint = listFor(kind);
		if (endpoint == null) return;
		String reason = unread.get(endpoint);
		if (reason != null) {
			throw new IOException(endpoint + " was not readable this cycle: "
					+ reason);
		}
	}

	/**
	 * Whether every Volume's children (LUNs from the LUN list, NFS exports
	 * from the share list, SSD caches from storage) are known this cycle.
	 * Own-adapter parents are full-set (the platform replaces a parent's
	 * children with what is emitted), so when they are not, no Volume is
	 * emitted as a parent and its existing edges are kept.
	 */
	static boolean volumeChildrenKnown(Map<String, String> unread) {
		return !unread.containsKey(SnapshotReads.LUNS)
				&& !unread.containsKey(SnapshotReads.SHARES);
	}

	/**
	 * Issue #21: what a reconfigure releases. The old object when the new
	 * configuration replaced it, {@code null} when it is still in use (or
	 * there was none), so the teardown never releases what the new
	 * configuration runs on.
	 */
	static <T> T replaced(T old, T current) {
		return old == current ? null : old;
	}
}
