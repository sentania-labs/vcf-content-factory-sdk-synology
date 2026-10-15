package com.vcfcf.adapters.synology;

import java.util.ArrayList;
import java.util.List;

/**
 * Build 31 (issue #13): the per-cycle budget. A snapshot's age counts from
 * when its build finished and reuse is decided by the collection cycle, so a
 * slow build is built once per cycle instead of once per resource; an
 * unreachable NAS costs one build attempt per cycle, so the cycle stays
 * inside the collection interval; Stop Collecting is not cached. Uses the
 * production timings (15-minute safety age). Run from the repo root
 * (ci/run_java_tests.sh).
 */
public final class SnapshotTimingTest {

	private static final long MIN = 60_000L;
	private static final long MAX_AGE = 15 * MIN;
	private static final int RESOURCES = 200;

	public static void main(String[] args) throws Exception {
		slowBuildIsFreshOnArrival();
		oneBuildPerCycle();
		safetyNetInAnEndlessCycle();
		unreachableNasCycleStaysInsideTheInterval();
		abortIsNotCached();
		System.out.println("SnapshotTimingTest: all assertions passed");
	}

	private static SnapshotCache<String> cache(SnapshotCacheTest.FakeClock c) {
		return new SnapshotCache<>(c, MAX_AGE, MIN, 15 * MIN, 60 * MIN);
	}

	/** builtAt is the end of a build that ran longer than the safety age. */
	static void slowBuildIsFreshOnArrival() throws Exception {
		SnapshotCacheTest.FakeClock clock = new SnapshotCacheTest.FakeClock();
		SnapshotCache<String> c = cache(clock);
		List<String> warns = new ArrayList<>();
		int[] builds = {0};
		long buildTime = MAX_AGE + 2 * MIN; // a very slow NAS
		SnapshotCache.Builder<String> slow = () -> {
			builds[0]++;
			clock.now += buildTime;
			return "snapshot#" + builds[0];
		};
		c.beginCycle();
		long started = clock.now;
		T.eq("snapshot#1", c.get(slow, warns::add), "first resource builds");
		T.eq(started + buildTime, c.builtAt(),
				"builtAt is when build() finished");
		for (int i = 1; i < RESOURCES; i++) {
			clock.now += 100L;
			T.eq("snapshot#1", c.get(slow, warns::add),
					"resource " + i + " reuses the snapshot");
		}
		T.eq(1, builds[0], "one build for the cycle, not one per resource");
	}

	/** The next cycle rebuilds even if it starts seconds later. */
	static void oneBuildPerCycle() throws Exception {
		SnapshotCacheTest.FakeClock clock = new SnapshotCacheTest.FakeClock();
		SnapshotCache<String> c = cache(clock);
		List<String> warns = new ArrayList<>();
		int[] builds = {0};
		SnapshotCache.Builder<String> b = () -> "snapshot#" + (++builds[0]);
		c.beginCycle();
		c.get(b, warns::add);
		clock.now += 10_000L;
		c.beginCycle();
		T.eq("snapshot#2", c.get(b, warns::add),
				"a new cycle never reuses the last cycle's snapshot");
		clock.now += 4 * MIN;
		T.eq("snapshot#2", c.get(b, warns::add),
				"the same cycle reuses it well past 60 s");
		T.eq(2, builds[0], "one build per cycle");
	}

	/** A cycle that never ends still refreshes after the safety age. */
	static void safetyNetInAnEndlessCycle() throws Exception {
		SnapshotCacheTest.FakeClock clock = new SnapshotCacheTest.FakeClock();
		SnapshotCache<String> c = cache(clock);
		List<String> warns = new ArrayList<>();
		int[] builds = {0};
		SnapshotCache.Builder<String> b = () -> "snapshot#" + (++builds[0]);
		c.beginCycle();
		c.get(b, warns::add);
		clock.now += MAX_AGE - 1;
		c.get(b, warns::add);
		T.eq(1, builds[0], "reused just inside the safety age");
		clock.now += 1;
		c.get(b, warns::add);
		T.eq(2, builds[0], "rebuilt at the safety age");
	}

	/**
	 * The issue's arithmetic: 25 resources against an unreachable NAS, each
	 * attempt three 30 s timeouts plus back-off. Before: about 38 minutes
	 * per cycle. Now: one attempt, under the 5-minute interval.
	 */
	static void unreachableNasCycleStaysInsideTheInterval() {
		SnapshotCacheTest.FakeClock clock = new SnapshotCacheTest.FakeClock();
		SnapshotCache<String> c = cache(clock);
		List<String> warns = new ArrayList<>();
		int[] attempts = {0};
		SnapshotCache.Builder<String> unreachable = () -> {
			attempts[0]++;
			clock.now += 93_000L;
			throw new SynologyTransportException("transport error calling "
					+ "SYNO.API.Auth login: connect timed out",
					SynologyFailures.Transport.CONNECT);
		};
		long start = clock.now;
		c.beginCycle();
		int down = 0;
		for (int i = 0; i < 25; i++) {
			try {
				c.get(unreachable, warns::add);
			} catch (Exception e) {
				if (SynologyFailures.isUnreachable(e)) down++;
			}
		}
		T.eq(1, attempts[0], "one build attempt for 25 resources");
		T.check(clock.now - start < 5 * MIN, "the cycle took "
				+ (clock.now - start) / 1000 + " s, inside the 5-minute interval");
		T.eq(25, down, "every resource of the cycle maps to DOWN");
	}

	/** Stop Collecting during a build is not a NAS failure. */
	static void abortIsNotCached() throws Exception {
		SnapshotCacheTest.FakeClock clock = new SnapshotCacheTest.FakeClock();
		SnapshotCache<String> c = cache(clock);
		List<String> warns = new ArrayList<>();
		c.beginCycle();
		try {
			c.get(() -> {
				throw new SnapshotCache.AbortedException("Stop Collecting");
			}, warns::add);
			throw new AssertionError("abort expected");
		} catch (SnapshotCache.AbortedException expected) {
			// expected
		}
		T.eq("ok", c.get(() -> "ok", warns::add),
				"the next call builds, even in the same cycle");
		T.eq(0, warns.size(), "no WARN for a stop");
	}
}
