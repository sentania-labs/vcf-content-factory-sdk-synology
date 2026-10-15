package com.vcfcf.adapters.synology;

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
		attemptCycleIsStacklessOnEveryAttempt();
		debugSinkIsTheStoredOne();
		throwingDebugSinkDoesNotPropagate();
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
		// devel_9_1 follow-up: the attempt cycle's caller gets the same
		// stackless, classified copy as the cached callers, so the
		// framework's discovery WARN is one line, not an 18-frame stack.
		Exception attempt = failures.get(0);
		T.check(attempt instanceof SnapshotCache.CachedFailureException,
				"the attempting resource gets the classified copy, not the "
						+ "original: " + attempt.getClass().getName());
		T.eq(0, attempt.getStackTrace().length,
				"no stack frames on the attempt cycle's exception");
		T.eq(null, attempt.getCause(), "no cause chain on the attempt copy");
		T.eq(SynologyFailures.authCode(failures.get(1)),
				SynologyFailures.authCode(attempt),
				"the attempt copy is classified as the cached copy");
		T.check(attempt.getMessage().contains("SynologyDsmException")
				&& attempt.getMessage().contains("error code 400"),
				"the attempt line keeps the original class and message: "
						+ attempt.getMessage());
		T.check(failures.get(1).getMessage().contains("SynologyDsmException"),
				"the cached line names the original class: "
						+ failures.get(1).getMessage());
		for (int i = 0; i < failures.size(); i++) {
			Exception e = failures.get(i);
			T.check(e instanceof SnapshotCache.CachedFailureException,
					"resource " + i + " fails from the cache");
			T.eq(400, SynologyFailures.authCode(e),
					"the cached failure carries its DSM 400");
			T.eq(null, e.getCause(), "no cause chain on the cached copy");
			T.eq(0, e.getStackTrace().length,
					"no stack trace on the cached copy");
			T.check(!SynologyFailures.isUnreachable(e),
					"a cached 400 is ERROR, not DOWN");
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
			// What SynologyApiClient.callRaw throws for a refused connect.
			throw new SynologyTransportException("transport error calling "
					+ "SYNO.API.Auth login: Connection refused",
					SynologyFailures.Transport.CONNECT);
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
		try {
			c.get(unreachable, warns::add);
			throw new AssertionError("cached failure expected");
		} catch (SnapshotCache.CachedFailureException e) {
			T.check(SynologyFailures.isUnreachable(e),
					"the cached copy of an unreachable failure is DOWN");
			T.eq(null, e.getCause(), "no cause chain on the cached copy");
			T.check(e.getMessage().contains("Connection refused"),
					"the message names the original: " + e.getMessage());
		} catch (Exception e) {
			throw new AssertionError("wrong failure " + e);
		}
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

	/**
	 * devel_9_1 follow-up: on the first cycle and on every back-off expiry
	 * (the cycles that try the login), the caller gets a stackless
	 * exception classified as the cached one, and the original, stack
	 * included, reaches only the debug sink. Covers a DSM login refusal
	 * (ERROR) and an unreachable NAS (DOWN).
	 */
	static void attemptCycleIsStacklessOnEveryAttempt() {
		FakeClock clock = new FakeClock();
		FakeNas nas = new FakeNas();
		nas.loginCode = 400;
		List<String> warns = new ArrayList<>();
		List<Exception> debugged = new ArrayList<>();
		SnapshotCache<String> c = new SnapshotCache<>(clock, MAX_AGE, RETRY,
				AUTH, AUTH_MAX, (line, e) -> debugged.add(e));
		for (int attemptNo = 1; attemptNo <= 2; attemptNo++) {
			c.beginCycle();
			Exception attempt = null;
			Exception cached = null;
			for (int i = 0; i < 2; i++) {
				try {
					c.get(nas::build, warns::add);
				} catch (Exception e) {
					if (i == 0) attempt = e;
					else cached = e;
				}
			}
			T.eq(attemptNo, nas.logins, "attempt " + attemptNo + " logged in");
			T.check(attempt instanceof SnapshotCache.CachedFailureException,
					"attempt " + attemptNo + " throws the classified copy");
			T.eq(0, attempt.getStackTrace().length,
					"attempt " + attemptNo + " has no stack frames");
			T.eq(attempt.getClass(), cached.getClass(),
					"attempt and cached are the same class");
			T.eq(400, SynologyFailures.authCode(attempt),
					"attempt " + attemptNo + " carries its DSM 400");
			T.eq(SynologyFailures.authCode(cached),
					SynologyFailures.authCode(attempt),
					"same login refusal classification as the cached copy");
			T.eq(SynologyFailures.isUnreachable(cached),
					SynologyFailures.isUnreachable(attempt),
					"same reachability classification as the cached copy");
			T.check(attempt.getMessage().contains("SynologyDsmException")
					&& attempt.getMessage().contains("error code 400"),
					"one line keeps class and message: " + attempt.getMessage());
			T.check(!attempt.getMessage().contains("\n"),
					"the message is one line");
			clock.now += AUTH_MAX + 1; // the back-off expires
		}
		T.eq(2, debugged.size(), "the debug sink gets each original once");
		T.check(debugged.get(0) instanceof SynologyDsmException
				&& debugged.get(0).getStackTrace().length > 0,
				"the debug sink gets the original with its stack");
		T.eq(2, warns.size(), "one WARN per failed build");

		// Unreachable: the attempt copy is DOWN exactly as the cached one.
		FakeClock clock2 = new FakeClock();
		SnapshotCache<String> c2 = cache(clock2);
		SnapshotCache.Builder<String> refused = () -> {
			throw new SynologyTransportException("transport error calling "
					+ "SYNO.API.Auth login: Connection refused",
					SynologyFailures.Transport.CONNECT);
		};
		c2.beginCycle();
		Exception down = null;
		Exception downCached = null;
		for (int i = 0; i < 2; i++) {
			try {
				c2.get(refused, warns::add);
			} catch (Exception e) {
				if (i == 0) down = e;
				else downCached = e;
			}
		}
		T.check(down instanceof SnapshotCache.CachedFailureException
				&& down.getStackTrace().length == 0,
				"an unreachable attempt is the stackless copy too");
		T.check(SynologyFailures.isUnreachable(down)
				&& SynologyFailures.isUnreachable(downCached),
				"the unreachable attempt and cached copies are both DOWN");
		T.check(down.getMessage().contains("SynologyTransportException")
				&& down.getMessage().contains("Connection refused"),
				"the unreachable line keeps class and message: "
						+ down.getMessage());
	}

	/** A debug sink that records which instance was called, and with what. */
	static final class RecordingSink
			implements java.util.function.BiConsumer<String, Exception> {
		final List<String> lines = new ArrayList<>();
		final List<Exception> originals = new ArrayList<>();

		@Override
		public void accept(String line, Exception e) {
			lines.add(line);
			originals.add(e);
		}
	}

	/**
	 * Factory issue #224: the DEBUG line goes to the sink stored at
	 * construction (the adapter stores the logger it resolved once in
	 * configureAdapter), on every failed build, never one resolved per
	 * failure. tests/test_snapshot_logger_once.py pins the adapter side.
	 */
	static void debugSinkIsTheStoredOne() {
		FakeClock clock = new FakeClock();
		FakeNas nas = new FakeNas();
		nas.loginCode = 400;
		RecordingSink stored = new RecordingSink();
		SnapshotCache<String> c = new SnapshotCache<>(clock, MAX_AGE, RETRY,
				AUTH, AUTH_MAX, stored);
		T.check(c.debugSink() == stored, "the cache keeps the sink it was given");
		List<String> warns = new ArrayList<>();
		cycle(c, nas, RESOURCES, warns);
		clock.now += AUTH_MAX + 1;
		cycle(c, nas, RESOURCES, warns);
		T.check(c.debugSink() == stored, "still the same sink after failures");
		T.eq(2, stored.originals.size(),
				"the stored sink got each failed build once");
		T.eq(warns.get(0), stored.lines.get(0),
				"the DEBUG line is the WARN line");
		T.check(stored.originals.get(1).getStackTrace().length > 0,
				"the stored sink gets the original stack");
	}

	/** A logger fault never reaches the collect path. */
	static void throwingDebugSinkDoesNotPropagate() {
		List<Throwable> faults = new ArrayList<>();
		faults.add(new IllegalStateException("logger closed"));
		faults.add(new NoSuchMethodError("Logger.debug"));
		for (Throwable fault : faults) {
			FakeClock clock = new FakeClock();
			FakeNas nas = new FakeNas();
			nas.loginCode = 400;
			int[] calls = {0};
			SnapshotCache<String> c = new SnapshotCache<>(clock, MAX_AGE,
					RETRY, AUTH, AUTH_MAX, (line, e) -> {
						calls[0]++;
						if (fault instanceof RuntimeException) {
							throw (RuntimeException) fault;
						}
						throw (Error) fault;
					});
			List<String> warns = new ArrayList<>();
			List<Exception> failures = cycle(c, nas, 3, warns);
			T.eq(1, calls[0], "the sink was called (" + fault + ")");
			T.eq(3, failures.size(), "every resource still fails normally");
			for (Exception e : failures) {
				T.check(e instanceof SnapshotCache.CachedFailureException,
						"the caller gets the classified copy, not the sink "
								+ "fault: " + e);
			}
			T.eq(400, SynologyFailures.authCode(failures.get(0)),
					"classification intact after a sink fault");
			T.eq(1, warns.size(), "the WARN was still written");
			T.eq(1, nas.logins, "still one login");
		}
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
