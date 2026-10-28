package com.vcfcf.adapters.synology;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Build 31 (review B2): the per-share NFS rule probe. A failed probe is
 * recorded, never folded into "no rules"; connect failures and login
 * refusals end the build. Driven with a fake NAS whose answer is the rule
 * count. Run from the repo root (ci/run_java_tests.sh).
 */
public final class NfsProbesTest {

	/** Fake NAS: share name to rule count, or to the failure it throws. */
	static final class FakeNas {
		final Map<String, Integer> rules = new HashMap<>();
		final Map<String, IOException> failing = new HashMap<>();
		final List<String> calls = new ArrayList<>();

		Integer load(String share) throws IOException {
			calls.add(share);
			IOException e = failing.get(share);
			if (e != null) throw e;
			return rules.getOrDefault(share, 0);
		}
	}

	public static void main(String[] args) throws Exception {
		exportsAreSharesWithRules();
		failedProbeIsRecordedNotZeroRules();
		answerWithNoRuleListIsAFailedProbe();
		connectFailureAndLoginRefusalEndTheBuild();
		answersAreReusedUntilTheRefresh();
		newShareIsProbedAtOnceAndGoneShareForgotten();
		failedShareIsRetriedAndWarnedHourly();
		clearForgetsEverything();
		serviceOffOnlyOnProof();
		System.out.println("NfsProbesTest: all assertions passed");
	}

	private static NfsProbes.Result<Integer> run(FakeNas nas,
			List<String> shares, List<String> warns) throws Exception {
		return NfsProbes.run(shares, nas::load, n -> n, next -> { },
				warns::add);
	}

	static void exportsAreSharesWithRules() throws Exception {
		FakeNas nas = new FakeNas();
		nas.rules.put("vmware", 2);
		List<String> warns = new ArrayList<>();
		NfsProbes.Result<Integer> r = run(nas,
				Arrays.asList("vmware", "homes"), warns);
		T.eq(Integer.valueOf(2), r.rulesByShare.get("vmware"), "vmware is an export");
		T.check(!r.rulesByShare.containsKey("homes"), "zero rules: not an export");
		T.check(r.failed.isEmpty(), "nothing failed");
		T.eq(0, warns.size(), "no WARN");
	}

	/** The B2 bug: a failed probe looked exactly like a share with no rules. */
	static void failedProbeIsRecordedNotZeroRules() throws Exception {
		FakeNas nas = new FakeNas();
		nas.rules.put("backups", 1);
		nas.failing.put("vmware", new IOException("SYNO.Core.FileServ.NFS."
				+ "SharePrivilege load failed: {\"error\":{\"code\":105}}"));
		List<String> warns = new ArrayList<>();
		NfsProbes.Result<Integer> r = run(nas,
				Arrays.asList("vmware", "backups"), warns);
		T.check(!r.rulesByShare.containsKey("vmware"),
				"a failed share is not an export with rules");
		T.check(r.failed.containsKey("vmware")
				&& r.failed.get("vmware").contains("105"),
				"the failed share is recorded with its reason: " + r.failed);
		T.eq(Integer.valueOf(1), r.rulesByShare.get("backups"),
				"the shares after it are still probed");
		T.eq(1, warns.size(), "one WARN for the failed share");
		T.check(warns.get(0).contains("vmware"), "the WARN names it: " + warns);
	}

	static void connectFailureAndLoginRefusalEndTheBuild() throws Exception {
		FakeNas nas = new FakeNas();
		nas.failing.put("a", new SynologyTransportException("transport error "
				+ "calling SYNO.Core.FileServ.NFS.SharePrivilege load: refused",
				SynologyFailures.Transport.CONNECT));
		try {
			run(nas, Arrays.asList("a", "b"), new ArrayList<>());
			throw new AssertionError("a connect failure must end the build");
		} catch (IOException e) {
			T.check(SynologyFailures.isUnreachable(e), "and is DOWN");
			T.eq(Arrays.asList("a"), nas.calls, "no further share is probed");
		}
		FakeNas refused = new FakeNas();
		refused.failing.put("a", new SynologyDsmException("Synology login "
				+ "failed: error code 400", 400, true));
		try {
			run(refused, Arrays.asList("a", "b"), new ArrayList<>());
			throw new AssertionError("a refused re-login must end the build");
		} catch (SynologyDsmException e) {
			T.eq(400, SynologyFailures.authCode(e), "the refusal is kept");
			T.eq(Arrays.asList("a"), refused.calls, "no further login");
		}
		// Review NIT-4: a probe that times out ends the build too.
		FakeNas hung = new FakeNas();
		hung.failing.put("a", new SynologyTransportException("transport error "
				+ "calling SYNO.Core.FileServ.NFS.SharePrivilege load: timed out",
				SynologyFailures.Transport.TIMEOUT));
		try {
			run(hung, Arrays.asList("a", "b", "c"), new ArrayList<>());
			throw new AssertionError("a probe timeout must end the build");
		} catch (IOException e) {
			T.check(SynologyFailures.isUnreachable(e), "and is DOWN");
			T.eq(Arrays.asList("a"), hung.calls, "no further share waits it out");
		}
	}

	// --- Build 32 (issue #20): NfsProbes.Cached ---

	static final long MIN = 60_000L;

	static final class Clock {
		long now = 1_000_000L;
	}

	static NfsProbes.Cached<Integer> cached(Clock c) {
		return new NfsProbes.Cached<>(() -> c.now, 15 * MIN, 60 * MIN);
	}

	static NfsProbes.Result<Integer> run(NfsProbes.Cached<Integer> p, FakeNas nas,
			List<String> shares, List<String> warns) throws Exception {
		return p.run(shares, nas::load, n -> n, next -> { }, warns::add);
	}

	static void answersAreReusedUntilTheRefresh() throws Exception {
		Clock c = new Clock();
		NfsProbes.Cached<Integer> p = cached(c);
		FakeNas nas = new FakeNas();
		nas.rules.put("vmware", 2);
		List<String> shares = Arrays.asList("vmware", "homes");
		run(p, nas, shares, new ArrayList<>());
		T.eq(2, nas.calls.size(), "first build probes every share");
		c.now += 5 * MIN;
		NfsProbes.Result<Integer> r = run(p, nas, shares, new ArrayList<>());
		T.eq(2, nas.calls.size(), "a build 5 minutes later probes nothing");
		T.eq(Integer.valueOf(2), r.rulesByShare.get("vmware"), "the export is remembered");
		T.check(!r.rulesByShare.containsKey("homes"), "the non-export too");
		c.now += 10 * MIN;
		nas.rules.put("vmware", 0);
		nas.rules.put("homes", 1);
		r = run(p, nas, shares, new ArrayList<>());
		T.eq(4, nas.calls.size(), "15 minutes after the refresh every share is probed");
		T.check(!r.rulesByShare.containsKey("vmware"), "a removed rule set is seen");
		T.eq(Integer.valueOf(1), r.rulesByShare.get("homes"), "a new rule set is seen");
	}

	static void newShareIsProbedAtOnceAndGoneShareForgotten() throws Exception {
		Clock c = new Clock();
		NfsProbes.Cached<Integer> p = cached(c);
		FakeNas nas = new FakeNas();
		nas.rules.put("a", 1);
		nas.rules.put("new", 3);
		run(p, nas, Arrays.asList("a"), new ArrayList<>());
		c.now += MIN;
		NfsProbes.Result<Integer> r = run(p, nas, Arrays.asList("a", "new"),
				new ArrayList<>());
		T.eq(Arrays.asList("a", "new"), nas.calls, "only the new share is probed");
		T.eq(Integer.valueOf(3), r.rulesByShare.get("new"), "and is an export now");
		c.now += MIN;
		r = run(p, nas, Arrays.asList("new"), new ArrayList<>());
		T.check(!r.rulesByShare.containsKey("a"), "a share no longer listed is gone");
		c.now += MIN;
		r = run(p, nas, Arrays.asList("a", "new"), new ArrayList<>());
		T.eq("a", nas.calls.get(nas.calls.size() - 1),
				"a share listed again is probed again, not served from memory");
	}

	static void failedShareIsRetriedAndWarnedHourly() throws Exception {
		Clock c = new Clock();
		NfsProbes.Cached<Integer> p = cached(c);
		FakeNas nas = new FakeNas();
		nas.rules.put("ok", 1);
		nas.failing.put("bad", new IOException("SharePrivilege load failed: "
				+ "{\"error\":{\"code\":105}}"));
		List<String> warns = new ArrayList<>();
		List<String> shares = Arrays.asList("ok", "bad");
		NfsProbes.Result<Integer> r = run(p, nas, shares, warns);
		T.check(r.failed.containsKey("bad"), "failure recorded");
		T.eq(1, warns.size(), "first failure WARNs");
		for (int i = 1; i <= 11; i++) {
			c.now += 5 * MIN;
			r = run(p, nas, shares, warns);
			T.check(r.failed.containsKey("bad"), "failure recorded every build (B2)");
		}
		long badCalls = nas.calls.stream().filter("bad"::equals).count();
		T.eq(12L, badCalls, "a failed share is probed again every build");
		T.eq(1, warns.size(), "no second WARN within the hour: " + warns);
		c.now += 5 * MIN;
		run(p, nas, shares, warns);
		T.eq(2, warns.size(), "one more WARN after an hour");
		T.check(warns.get(1).contains("at most once every 60 minutes"),
				"the WARN says it is rate-limited: " + warns.get(1));
		nas.failing.remove("bad");
		c.now += MIN;
		r = run(p, nas, shares, warns);
		T.check(r.failed.isEmpty(), "recovered");
		nas.failing.put("bad", new IOException("again"));
		c.now += 15 * MIN;
		run(p, nas, shares, warns);
		T.eq(3, warns.size(), "a new failure after recovery WARNs at once");
	}

	static void clearForgetsEverything() throws Exception {
		Clock c = new Clock();
		NfsProbes.Cached<Integer> p = cached(c);
		FakeNas nas = new FakeNas();
		nas.rules.put("a", 1);
		run(p, nas, Arrays.asList("a"), new ArrayList<>());
		p.clear();
		c.now += MIN;
		run(p, nas, Arrays.asList("a"), new ArrayList<>());
		T.eq(2, nas.calls.size(), "after clear every share is probed again");
	}

	static void serviceOffOnlyOnProof() {
		T.check(NfsProbes.serviceOff(true, false, false), "read, present, false: off");
		T.check(!NfsProbes.serviceOff(true, false, true), "enabled: probe");
		T.check(!NfsProbes.serviceOff(false, false, false), "unread: probe as before");
		T.check(!NfsProbes.serviceOff(true, true, false), "field missing: probe as before");
	}

	/**
	 * Build 35 review: a success-shaped answer with no rule list (the count
	 * function returns -1) is a failed probe, recorded and not remembered,
	 * never "not an export".
	 */
	static void answerWithNoRuleListIsAFailedProbe() throws Exception {
		FakeNas nas = new FakeNas();
		nas.rules.put("vmware", -1);
		nas.rules.put("backups", 1);
		List<String> warns = new ArrayList<>();
		NfsProbes.Cached<Integer> p = new NfsProbes.Cached<>(() -> 0L,
				15 * 60_000L, 0L);
		NfsProbes.Result<Integer> r = p.run(Arrays.asList("vmware", "backups"),
				nas::load, n -> n, next -> { }, warns::add);
		T.check(r.failed.containsKey("vmware"), "no rule list: failed " + r.failed);
		T.check(!r.rulesByShare.containsKey("vmware"), "and not an export");
		T.eq(Integer.valueOf(1), r.rulesByShare.get("backups"), "others probed");
		T.eq(1, warns.size(), "one WARN");
		// Not remembered: the next build probes it again, inside the refresh.
		nas.calls.clear();
		p.run(Arrays.asList("vmware", "backups"), nas::load, n -> n, next -> { },
				warns::add);
		T.check(nas.calls.contains("vmware"), "probed again next build");
		T.check(!nas.calls.contains("backups"), "a good answer is reused");
	}
}
