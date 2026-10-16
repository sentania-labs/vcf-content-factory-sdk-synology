package com.vcfcf.adapters.synology;

import static com.vcfcf.adapters.synology.TopologyTest.MAPS;
import static com.vcfcf.adapters.synology.TopologyTest.obj;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Build 32 (issue #28): the Suite API Datastore list is paged to
 * pageInfo.totalCount, and the path index is reloaded at most every 30
 * minutes or when a new path misses. Run from the repo root
 * (ci/run_java_tests.sh).
 */
public final class DatastoreIndexTest {

	public static void main(String[] args) throws Exception {
		pagesUntilTotalCount();
		pagesUntilShortPageWithoutTotal();
		emptyAndFailingPages();
		reloadAtMostEveryTtl();
		newMissReloadsOnceKnownMissDoesNot();
		failedLoadRetriesNextCycle();
		emptyPageBeforeTotalIsAFailedLoad();
		duplicateRowsAreIndexedOnce();
		System.out.println("DatastoreIndexTest: all assertions passed");
	}

	/** A Suite API with {@code n} datastores; records the pages asked for. */
	static final class FakeSuiteApi {
		final int n;
		final boolean sendTotal;
		final List<String> asked = new ArrayList<>();
		int failOnPage = -1;

		FakeSuiteApi(int n, boolean sendTotal) {
			this.n = n;
			this.sendTotal = sendTotal;
		}

		Object page(int page, int size) throws Exception {
			asked.add(page + "/" + size);
			if (page == failOnPage) throw new java.io.IOException("HTTP 503");
			List<Object> rows = new ArrayList<>();
			for (int i = page * size; i < Math.min(n, (page + 1) * size); i++) {
				rows.add(obj("resourceKey", obj("name", "ds" + i)));
			}
			Map<String, Object> root = obj("resourceList", rows);
			if (sendTotal) {
				root.put("pageInfo", obj("totalCount", n, "page", page,
						"pageSize", size));
			}
			return root;
		}
	}

	static void pagesUntilTotalCount() throws Exception {
		FakeSuiteApi api = new FakeSuiteApi(2500, true);
		List<Object> all = DatastoreIndex.fetchAll(MAPS, api::page, 1000, w -> {
			throw new AssertionError("no WARN: " + w);
		});
		T.eq(2500, all.size(), "every datastore past the first page");
		T.eq(3, api.asked.size(), "three pages: " + api.asked);
		T.eq("2/1000", api.asked.get(2), "0-based pages of 1000");
		FakeSuiteApi exact = new FakeSuiteApi(2000, true);
		DatastoreIndex.fetchAll(MAPS, exact::page, 1000, w -> { });
		T.eq(2, exact.asked.size(), "stops at totalCount, no empty extra page");
		T.eq(1000, DatastoreIndex.PAGE_SIZE, "smaller page than build 31's 10000");
	}

	static void pagesUntilShortPageWithoutTotal() throws Exception {
		FakeSuiteApi api = new FakeSuiteApi(1500, false);
		List<Object> all = DatastoreIndex.fetchAll(MAPS, api::page, 1000, w -> { });
		T.eq(1500, all.size(), "without totalCount a short page ends it");
		T.eq(2, api.asked.size(), "two pages");
	}

	static void emptyAndFailingPages() throws Exception {
		FakeSuiteApi none = new FakeSuiteApi(0, true);
		T.eq(0, DatastoreIndex.fetchAll(MAPS, none::page, 1000, w -> { }).size(),
				"no datastores");
		T.eq(1, none.asked.size(), "one request");
		FakeSuiteApi broken = new FakeSuiteApi(2500, true);
		broken.failOnPage = 1;
		try {
			DatastoreIndex.fetchAll(MAPS, broken::page, 1000, w -> { });
			throw new AssertionError("a failed page must throw, not return page 0 only");
		} catch (java.io.IOException expected) {
			// the caller has no index this cycle, as before build 32
		}
		FakeSuiteApi huge = new FakeSuiteApi(Integer.MAX_VALUE, true);
		List<String> warns = new ArrayList<>();
		List<Object> capped = DatastoreIndex.fetchAll(MAPS, huge::page, 10, warns::add);
		T.eq(DatastoreIndex.MAX_PAGES * 10, capped.size(), "safety cap");
		T.eq(1, warns.size(), "the cap is named");
	}

	static final long MIN = 60_000L;

	static void reloadAtMostEveryTtl() {
		long[] now = {0L};
		DatastoreIndex.ReloadPolicy p = new DatastoreIndex.ReloadPolicy(
				() -> now[0], 30 * MIN);
		T.check(p.beginCycle(), "first cycle loads");
		p.loaded();
		for (int i = 1; i < 6; i++) {
			now[0] += 5 * MIN;
			T.check(!p.beginCycle(), "cycle " + i + " reuses the index");
		}
		now[0] += 5 * MIN;
		T.check(p.beginCycle(), "30 minutes after the load it reloads");
	}

	static void newMissReloadsOnceKnownMissDoesNot() {
		long[] now = {0L};
		DatastoreIndex.ReloadPolicy p = new DatastoreIndex.ReloadPolicy(
				() -> now[0], 30 * MIN);
		p.beginCycle();
		p.loaded();
		// Misses against the index loaded this very cycle are real misses.
		p.miss("VMFS:|naa.unused|");
		p.miss("10.0.0.2/volume1/nfs");
		now[0] += 5 * MIN;
		T.check(!p.beginCycle(), "known misses do not reload");
		p.miss("VMFS:|naa.unused|");
		now[0] += 5 * MIN;
		T.check(!p.beginCycle(), "still no reload for a known miss");
		p.miss("VMFS:|naa.new-lun|");
		now[0] += 5 * MIN;
		T.check(p.beginCycle(), "a new path missing reloads at the next cycle");
		p.loaded();
		p.miss("VMFS:|naa.new-lun|");
		now[0] += 5 * MIN;
		T.check(!p.beginCycle(), "and once only");
		p.hit("VMFS:|naa.new-lun|");
		p.miss("VMFS:|naa.new-lun|");
		now[0] += 5 * MIN;
		T.check(p.beginCycle(), "a path that matched and now misses reloads");
	}

	static void failedLoadRetriesNextCycle() {
		long[] now = {0L};
		DatastoreIndex.ReloadPolicy p = new DatastoreIndex.ReloadPolicy(
				() -> now[0], 30 * MIN);
		p.beginCycle();
		p.failed();
		now[0] += 5 * MIN;
		T.check(p.beginCycle(), "a failed first load is retried");
		p.loaded();
		now[0] += 5 * MIN;
		T.check(!p.beginCycle(), "then reused");
		T.eq(5 * MIN, p.ageMs(), "age");
	}

	/** Review N3: the list shrank while paged; never cache the partial list. */
	static void emptyPageBeforeTotalIsAFailedLoad() throws Exception {
		DatastoreIndex.PageFetcher<Object> shrinking = (page, size) -> page == 0
				? obj("resourceList", java.util.Arrays.asList(obj("n", 1), obj("n", 2)),
						"pageInfo", obj("totalCount", 5))
				: obj("resourceList", new ArrayList<>(), "pageInfo", obj("totalCount", 5));
		try {
			DatastoreIndex.fetchAll(MAPS, shrinking, 2, w -> { });
			throw new AssertionError("an empty page before totalCount must fail the load");
		} catch (java.io.IOException expected) {
			T.check(expected.getMessage().contains("2 of 5"), expected.getMessage());
		}
	}

	/** Review N3: one Datastore identity is indexed once per path. */
	static void duplicateRowsAreIndexedOnce() {
		java.util.Set<String> seen = new java.util.HashSet<>();
		List<String[]> ids = java.util.Arrays.asList(
				new String[] {"VMEntityObjectID", "datastore-1", "true"},
				new String[] {"DataStrorePath", "VMFS:|naa.1|", "false"});
		T.check(DatastoreIndex.firstSighting(seen, "VMFS:|naa.1|", "ds1", ids), "first");
		T.check(!DatastoreIndex.firstSighting(seen, "VMFS:|naa.1|", "ds1", ids), "duplicate");
		List<String[]> other = java.util.Arrays.asList(
				new String[] {"VMEntityObjectID", "datastore-9", "true"},
				new String[] {"DataStrorePath", "VMFS:|naa.1|", "false"});
		T.check(DatastoreIndex.firstSighting(seen, "VMFS:|naa.1|", "ds1", other),
				"another vCenter's copy of the same path is kept");
	}
}
