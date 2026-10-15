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
		connectFailureAndLoginRefusalEndTheBuild();
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
}
