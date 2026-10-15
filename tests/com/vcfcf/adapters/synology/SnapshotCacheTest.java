package com.vcfcf.adapters.synology;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Build 31 (issue #12): a failed snapshot build is cached, so the other
 * resources of the cycle fail fast without a new DSM login, and an
 * authentication-class refusal is not retried before its back-off (doubled
 * per consecutive refusal) or an instance edit. Driven with a fake clock and
 * a fake NAS whose login refusal is the DSM shape: an error code in the body
 * of a 200, thrown by SynologyApiClient.login() as a SynologyDsmException.
 * Run from the repo root (ci/run_java_tests.sh).
 */
public final class SnapshotCacheTest {

	private static final long MIN = 60_000L;
	private static final long MAX_AGE = 15 * MIN;
	private static final long RETRY = MIN;
	private static final long AUTH = 15 * MIN;
	private static final long AUTH_MAX = 60 * MIN;
	private static final int RESOURCES = 200;

	/** Fake time. */
	static final class FakeClock implements SnapshotCache.Clock {
		long now = 1_000_000L;

		@Override
		public long millis() {
			return now;
		}
	}

	/** Fake NAS: counts SYNO.API.Auth logins, answers each with a DSM code. */
	static final class FakeNas {
		int logins;
		/** 0 = success; otherwise the DSM error code in the 200 body. */
		int loginCode;

		/** One snapshot build: log in, then read. */
		String build() throws Exception {
			logins++;
			if (loginCode != 0) {
				// What SynologyApiClient.login() throws for success:false.
				throw new SynologyDsmException("Synology login failed: error "
						+ "code " + loginCode, loginCode, true);
			}
			return "snapshot#" + logins;
		}
	}

	public static void main(String[] args) throws Exception {
		healthyNasOneBuildPerCycle();
		wrongPasswordOneLoginPerCycle();
		authBackoffDoublesAcrossCycles();
		successResetsTheDoubling();
		everyAuthCodeHoldsTheNextCycle();
		otherCodesAreRetriedNextCycle();
		instanceEditTriesAtOnce();
		unreachableFailsFastAndRetriesNextCycle();
		failureHoldsForTheRestOfTheCycle();
		interruptIsNotCached();
		System.out.println("SnapshotCacheTest: all assertions passed");
	}

	private static SnapshotCache<String> cache(FakeClock clock) {
		return new SnapshotCache<>(clock, MAX_AGE, RETRY, AUTH, AUTH_MAX);
	}

	/** Collects one cycle: n resources, each asking for the snapshot. */
	private static List<Exception> cycle(SnapshotCache<String> c, FakeNas nas,
			int n, List<String> warns) {
		c.beginCycle();
		List<Exception> failures = new ArrayList<>();
		for (int i = 0; i < n; i++) {
			try {
				c.get(nas::build, warns::add);
			} catch (Exception e) {
				failures.add(e);
			}
		}
		return failures;
	}

	/** Must not regress: a healthy NAS is read once per cycle. */
	static void healthyNasOneBuildPerCycle() {
		FakeClock clock = new FakeClock();
		FakeNas nas = new FakeNas();
		SnapshotCache<String> c = cache(clock);
		List<String> warns = new ArrayList<>();
		T.eq(0, cycle(c, nas, RESOURCES, warns).size(), "no failures");
		T.eq(1, nas.logins, "one build serves every resource");
		T.eq(0, warns.size(), "no WARN");
	}

	/** The issue: one login per resource. Now: one per cycle at most. */
	static void wrongPasswordOneLoginPerCycle() {
		FakeClock clock = new FakeClock();
		FakeNas nas = new FakeNas();
		nas.loginCode = 400;
		SnapshotCache<String> c = cache(clock);
		List<String> warns = new ArrayList<>();
		List<Exception> failures = cycle(c, nas, RESOURCES, warns);
		T.eq(1, nas.logins, "no second login after an auth error in the cycle");
		T.eq(RESOURCES, failures.size(), "every resource fails");
		T.check(failures.get(0) instanceof SynologyDsmException,
				"the first resource gets the original failure");
		for (int i = 1; i < failures.size(); i++) {
			Exception e = failures.get(i);
			T.check(e instanceof SnapshotCache.CachedFailureException,
					"resource " + i + " fails from the cache");
			T.eq(400, SynologyFailures.authCode(e),
					"the cached failure keeps its DSM 400 in the cause chain");
			T.check(e.getMessage().contains("error code 400"),
					"the cached message says why: " + e.getMessage());
		}
		T.eq(1, warns.size(), "one WARN for the failed build");
		T.check(warns.get(0).contains("DSM login error 400")
				&& warns.get(0).contains("no login is tried")
				&& warns.get(0).contains("Auto Block"),
				"the WARN names the refusal: " + warns.get(0));
	}

	/** 15 min, then 30, then 60, then capped at 60 (cycles every 5 min). */
	static void authBackoffDoublesAcrossCycles() {
		FakeClock clock = new FakeClock();
		FakeNas nas = new FakeNas();
		nas.loginCode = 400;
		SnapshotCache<String> c = cache(clock);
		List<String> warns = new ArrayList<>();
		long[] loginAt = new long[6];
		int seen = 0;
		long start = clock.now;
		for (int k = 0; k < 60; k++) { // 5 hours of 5-minute cycles
			int before = nas.logins;
			cycle(c, nas, RESOURCES, warns);
			if (nas.logins > before && seen < loginAt.length) {
				loginAt[seen++] = clock.now - start;
			}
			clock.now += 5 * MIN;
		}
		T.eq(0L, loginAt[0], "first login at once");
		T.eq(15 * MIN, loginAt[1] - loginAt[0], "second after 15 min");
		T.eq(30 * MIN, loginAt[2] - loginAt[1], "third after 30 min");
		T.eq(60 * MIN, loginAt[3] - loginAt[2], "fourth after 60 min");
		T.eq(60 * MIN, loginAt[4] - loginAt[3], "capped at 60 min");
		T.eq(nas.logins, warns.size(), "one WARN per failed login, none per resource");
		T.check(nas.logins <= 7, "at most 7 failed logins in 5 hours, got "
				+ nas.logins);
		T.check(warns.get(1).contains("2 in a row"),
				"the WARN counts consecutive refusals: " + warns.get(1));
	}

	static void successResetsTheDoubling() {
		FakeClock clock = new FakeClock();
		FakeNas nas = new FakeNas();
		SnapshotCache<String> c = cache(clock);
		List<String> warns = new ArrayList<>();
		nas.loginCode = 400;
		cycle(c, nas, 1, warns);
		clock.now += AUTH;
		cycle(c, nas, 1, warns); // second refusal: 30 min
		clock.now += 2 * AUTH;
		nas.loginCode = 0;
		T.eq(0, cycle(c, nas, 1, warns).size(), "collects once the NAS accepts");
		nas.loginCode = 400; // password rotated again later
		clock.now += 10 * MIN;
		cycle(c, nas, 1, warns);
		int logins = nas.logins;
		clock.now += AUTH;
		cycle(c, nas, 1, warns);
		T.eq(logins + 1, nas.logins, "back to 15 min after a success");
	}

	/** Every SYNO.API.Auth refusal code holds the next cycle. */
	static void everyAuthCodeHoldsTheNextCycle() {
		for (int code : new int[] {400, 401, 402, 403, 404, 406, 407, 408, 409,
				410}) {
			FakeClock clock = new FakeClock();
			FakeNas nas = new FakeNas();
			nas.loginCode = code;
			SnapshotCache<String> c = cache(clock);
			List<String> warns = new ArrayList<>();
			cycle(c, nas, RESOURCES, warns);
			clock.now += 5 * MIN;
			cycle(c, nas, RESOURCES, warns);
			T.eq(1, nas.logins, "DSM login error " + code
					+ " holds the next cycle");
		}
	}

	/**
	 * A login error that is not a credential refusal, and a 400 from an API
	 * other than the login (DSM reuses code numbers per API), are retried
	 * the next cycle.
	 */
	static void otherCodesAreRetriedNextCycle() {
		for (int code : new int[] {100, 105, 0}) {
			FakeClock clock = new FakeClock();
			FakeNas nas = new FakeNas();
			SnapshotCache<String> c = cache(clock);
			List<String> warns = new ArrayList<>();
			int[] attempts = {0};
			SnapshotCache.Builder<String> b = () -> {
				attempts[0]++;
				throw new SynologyDsmException("Synology login failed: error "
						+ "code " + code, code, true);
			};
			for (int k = 0; k < 2; k++) {
				c.beginCycle();
				try {
					c.get(b, warns::add);
				} catch (Exception expected) {
					// expected
				}
				clock.now += 5 * MIN;
			}
			T.eq(2, attempts[0], "login error " + code + " is retried");
		}
		FakeClock clock = new FakeClock();
		SnapshotCache<String> c = cache(clock);
		List<String> warns = new ArrayList<>();
		int[] attempts = {0};
		SnapshotCache.Builder<String> dataApi400 = () -> {
			attempts[0]++;
			throw new SynologyDsmException("SYNO.Core.Share list failed",
					400, false);
		};
		for (int k = 0; k < 2; k++) {
			c.beginCycle();
			try {
				c.get(dataApi400, warns::add);
			} catch (Exception expected) {
				// expected
			}
			clock.now += 5 * MIN;
		}
		T.eq(2, attempts[0], "a 400 from a non-login API is not auth class");
	}

	/** A new configuration gets a new cache: it may log in at once. */
	static void instanceEditTriesAtOnce() {
		FakeClock clock = new FakeClock();
		FakeNas nas = new FakeNas();
		nas.loginCode = 400;
		List<String> warns = new ArrayList<>();
		cycle(cache(clock), nas, RESOURCES, warns);
		nas.loginCode = 0; // the admin fixed the password
		SnapshotCache<String> edited = cache(clock); // configure publishes a new one
		T.eq(0, cycle(edited, nas, RESOURCES, warns).size(),
				"the edited instance collects in the same minute");
		T.eq(2, nas.logins, "one login for the edited instance");
	}

	/** An unreachable NAS costs one attempt per cycle, not per resource. */
	static void unreachableFailsFastAndRetriesNextCycle() {
		FakeClock clock = new FakeClock();
		int[] attempts = {0};
		SnapshotCache<String> c = cache(clock);
		List<String> warns = new ArrayList<>();
		SnapshotCache.Builder<String> unreachable = () -> {
			attempts[0]++;
			clock.now += 93_000L; // three 30 s timeouts plus back-off
			throw new IOException("transport error calling SYNO.API.Auth "
					+ "login: Connection refused");
		};
		c.beginCycle();
		for (int i = 0; i < RESOURCES; i++) {
			try {
				c.get(unreachable, warns::add);
			} catch (Exception e) {
				// expected
			}
		}
		T.eq(1, attempts[0], "one attempt for the whole cycle");
		T.eq(1, warns.size(), "one WARN");
		clock.now += 5 * MIN;
		c.beginCycle();
		try {
			c.get(unreachable, warns::add);
		} catch (Exception e) {
			// expected
		}
		T.eq(2, attempts[0], "tried again the next cycle");
	}

	/** Even after the short back-off, the same cycle is not retried. */
	static void failureHoldsForTheRestOfTheCycle() {
		FakeClock clock = new FakeClock();
		FakeNas nas = new FakeNas();
		nas.loginCode = 105;
		SnapshotCache<String> c = cache(clock);
		List<String> warns = new ArrayList<>();
		c.beginCycle();
		try {
			c.get(nas::build, warns::add);
		} catch (Exception e) {
			// expected
		}
		clock.now += 10 * MIN; // a very long cycle
		try {
			c.get(nas::build, warns::add);
			throw new AssertionError("cached failure expected");
		} catch (SnapshotCache.CachedFailureException e) {
			// expected
		} catch (Exception e) {
			throw new AssertionError("wrong failure " + e);
		}
		T.eq(1, nas.logins, "no second build in the same cycle");
	}

	/** Stop Collecting is not a NAS failure. */
	static void interruptIsNotCached() throws Exception {
		FakeClock clock = new FakeClock();
		SnapshotCache<String> c = cache(clock);
		List<String> warns = new ArrayList<>();
		c.beginCycle();
		try {
			c.get(() -> {
				throw new InterruptedException("stop");
			}, warns::add);
			throw new AssertionError("interrupt expected");
		} catch (InterruptedException expected) {
			// expected
		}
		T.eq("ok", c.get(() -> "ok", warns::add), "next call builds");
		T.eq(0, warns.size(), "no WARN for an interrupt");
	}
}
