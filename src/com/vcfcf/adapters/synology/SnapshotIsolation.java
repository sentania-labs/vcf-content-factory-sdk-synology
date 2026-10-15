package com.vcfcf.adapters.synology;

import java.io.IOException;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

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
	 * Review B2: throws when the NFS rule probe of this share failed this
	 * cycle ({@code probeFailed} maps a share name to a one-line reason).
	 * Its rules were not read, so an existing NFS export on it is ERROR
	 * instead of publishing {@code rule_count = 0} and an empty
	 * {@code allowed_clients} as if it had no rules.
	 */
	static void requireNfsProbe(Map<String, String> probeFailed,
			String share) throws IOException {
		String reason = share == null ? null : probeFailed.get(share);
		if (reason != null) {
			throw new IOException("NFS rules of share " + share + " were not "
					+ "readable this cycle (SYNO.Core.FileServ.NFS."
					+ "SharePrivilege): " + reason);
		}
	}

	/**
	 * Which Volumes' children (LUNs from the LUN list, NFS exports from the
	 * share list and the per-share NFS rule probes, SSD caches from
	 * storage) are known this cycle. Own-adapter parents are full-set (the
	 * platform replaces a parent's children with what is emitted), so a
	 * Volume whose children are not known is not emitted as a parent and
	 * its existing edges are kept.
	 */
	static final class VolumeChildren {
		/** Every Volume's children are known. */
		static final VolumeChildren ALL =
				new VolumeChildren(false, Collections.emptySet());
		/** No Volume's children are known. */
		static final VolumeChildren NONE =
				new VolumeChildren(true, Collections.emptySet());

		/** True when no Volume's children are known (a list was unread). */
		private final boolean none;
		/** The {@code vol_path} of each Volume whose children are not known. */
		private final Set<String> frozen;

		private VolumeChildren(boolean none, Set<String> frozen) {
			this.none = none;
			this.frozen = Collections.unmodifiableSet(frozen);
		}

		/** Whether the children of the Volume at {@code volPath} are known. */
		boolean known(String volPath) {
			return !none && !(volPath != null && frozen.contains(volPath));
		}

		/** Whether every Volume's children are known. */
		boolean allKnown() {
			return !none && frozen.isEmpty();
		}

		/** Whether no Volume's children are known. */
		boolean noneKnown() {
			return none;
		}

		/** The {@code vol_path}s frozen this cycle (empty when none or all). */
		Set<String> frozenVolumes() {
			return frozen;
		}
	}

	/**
	 * Decides {@link VolumeChildren} for this cycle. An unread LUN or share
	 * list leaves no Volume's children known. Review B2: a share whose NFS
	 * rule probe failed may be an export, so its Volume is not emitted
	 * without it (that would drop the export's Volume edge for the cycle).
	 * Review NIT-R3: only that Volume, the one at the share's
	 * {@code vol_path}; every other Volume refreshes. A failed share whose
	 * {@code vol_path} is unknown could sit on any Volume, so then none is
	 * known.
	 *
	 * @param volPathByShare each share's {@code vol_path} from the share list
	 */
	static VolumeChildren volumeChildren(Map<String, String> unread,
			Map<String, String> probeFailed, Map<String, String> volPathByShare) {
		if (unread.containsKey(SnapshotReads.LUNS)
				|| unread.containsKey(SnapshotReads.SHARES)) {
			return VolumeChildren.NONE;
		}
		Set<String> frozen = new TreeSet<>();
		for (String share : probeFailed.keySet()) {
			String volPath = volPathByShare.get(share);
			if (volPath == null || volPath.isEmpty()) {
				return VolumeChildren.NONE;
			}
			frozen.add(volPath);
		}
		return frozen.isEmpty() ? VolumeChildren.ALL
				: new VolumeChildren(false, frozen);
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
