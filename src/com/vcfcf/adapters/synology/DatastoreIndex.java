package com.vcfcf.adapters.synology;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.LongSupplier;

/**
 * Build 32 (issue #28): how the Datastore cross-link reads the VMWARE
 * Datastore list from the Suite API, and when it reads it again. No VCF Ops
 * SDK and no framework-jar dependency (generic over the JSON node type
 * through {@link SynologyTopology.Json}), so the plain-main tests exercise
 * exactly the code {@link SynologyStitcher} runs.
 *
 * <p>Before build 32 the list was fetched every relationship cycle as one
 * request with {@code pageSize=10000} and no paging: datastores past the
 * first 10,000 silently lost their cross-link, and every cycle of every
 * instance pulled the whole list.
 */
final class DatastoreIndex {

	private DatastoreIndex() {}

	/** Rows per Suite API page. */
	static final int PAGE_SIZE = 1000;

	/** Safety cap on pages per load (100,000 datastores at {@link #PAGE_SIZE}). */
	static final int MAX_PAGES = 100;

	/** Fetches one page (0-based) of {@code GET /api/resources} and parses it. */
	interface PageFetcher<J> {
		J page(int page, int pageSize) throws Exception;
	}

	/**
	 * Review N3: whether this identity was already seen under this key
	 * ({@code path}; the stitcher passes "" to dedupe whole rows). A row
	 * shifted across a page boundary while the list changes can come back
	 * twice; it is counted and indexed once, so no Datastore gets the same
	 * child twice.
	 */
	static boolean firstSighting(java.util.Set<String> seen, String path,
			String name, List<String[]> identifiers) {
		StringBuilder sig = new StringBuilder(path).append('\u0000').append(name);
		for (String[] id : identifiers) {
			sig.append('\u0000').append(id == null ? "" : String.join("=", id));
		}
		return seen.add(sig.toString());
	}

	/**
	 * Every {@code resourceList} entry across all pages. Pages until the
	 * collected count reaches {@code pageInfo.totalCount}, a page comes back
	 * empty, or (when the Suite API sends no {@code totalCount}) a page is
	 * short. Stops at {@link #MAX_PAGES} pages and says so through
	 * {@code warn}. A failed page, or an empty page before
	 * {@code totalCount} is reached (review N3), throws (the caller then has
	 * no index for the cycle, as before): a partial list is never passed
	 * off as the whole one.
	 */
	static <J> List<J> fetchAll(SynologyTopology.Json<J> js, PageFetcher<J> fetch,
			int pageSize, java.util.function.Consumer<String> warn) throws Exception {
		List<J> all = new ArrayList<>();
		for (int page = 0; page < MAX_PAGES; page++) {
			J root = fetch.page(page, pageSize);
			List<J> rows = js.list(js.get(root, "resourceList"));
			all.addAll(rows);
			Double total = SynologyMetrics.num(js,
					js.get(js.get(root, "pageInfo"), "totalCount"));
			if (rows.isEmpty()) {
				// Review N3: an empty page before totalCount is reached (the
				// list shrank while it was paged) is a failed load, never a
				// partial index passed off as the whole one.
				if (total != null && all.size() < total) {
					throw new java.io.IOException("Suite API Datastore list: page "
							+ page + " was empty after " + all.size() + " of "
							+ total.longValue() + " entries");
				}
				return all;
			}
			if (total != null ? all.size() >= total : rows.size() < pageSize) {
				return all;
			}
		}
		warn.accept("SynologyStitcher: VMWARE Datastore list longer than "
				+ MAX_PAGES + " pages of " + pageSize + "; the cross-link uses "
				+ "the first " + all.size());
		return all;
	}

	/**
	 * When the Datastore path index is loaded again: at most every
	 * {@code ttlMs}, or at the next cycle after a path that was not missed
	 * before misses against an index loaded in an earlier cycle (a new LUN
	 * or export, or a Datastore created for one). A path that keeps missing
	 * (a LUN no Datastore uses, an NFS path through a NAS address ESXi did
	 * not mount) does not reload the index every cycle. A failed load is
	 * retried the next cycle. Thread safe.
	 */
	static final class ReloadPolicy {
		private final LongSupplier clock;
		private final long ttlMs;
		private final Set<String> knownMisses = new HashSet<>();
		private boolean loadedOnce;
		private boolean lastFailed;
		private boolean dirty;
		private boolean fresh;
		private long loadedAt;

		ReloadPolicy(LongSupplier clock, long ttlMs) {
			this.clock = clock;
			this.ttlMs = ttlMs;
		}

		/** Start of a cross-link pass: whether the index must be loaded now. */
		synchronized boolean beginCycle() {
			fresh = false;
			boolean due = !loadedOnce || lastFailed || dirty
					|| clock.getAsLong() - loadedAt >= ttlMs;
			if (due) dirty = false;
			return due;
		}

		/** The index was loaded this cycle. */
		synchronized void loaded() {
			loadedOnce = true;
			lastFailed = false;
			fresh = true;
			loadedAt = clock.getAsLong();
		}

		/** The load failed; try again next cycle. */
		synchronized void failed() {
			lastFailed = true;
		}

		/** A computed path matched no Datastore. */
		synchronized void miss(String path) {
			if (knownMisses.add(path) && !fresh) dirty = true;
		}

		/** A computed path matched. */
		synchronized void hit(String path) {
			knownMisses.remove(path);
		}

		/** Milliseconds since the last successful load (0 before the first). */
		synchronized long ageMs() {
			return loadedOnce ? clock.getAsLong() - loadedAt : 0L;
		}
	}
}
