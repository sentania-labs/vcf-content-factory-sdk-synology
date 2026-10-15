package com.vcfcf.adapters.synology;

import java.io.IOException;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Build 31 (review B2): the per-share NFS rule probe of one snapshot build
 * ({@code SYNO.Core.FileServ.NFS.SharePrivilege load}, which is what makes a
 * share an NFS export). No VCF Ops SDK and no framework-jar dependency
 * (generic over the JSON type), so the plain-main tests drive it with a fake
 * NAS; the adapter's {@code Snapshot} delegates here.
 *
 * <p>A share with more than zero rules is an export. A share whose probe
 * failed is neither: it is recorded in {@link Result#failed}, so an existing
 * export on it is ERROR and Volume edges are left unchanged for the cycle,
 * instead of the export publishing {@code rule_count = 0}. A probe that
 * cannot connect or times out, or whose session re-login is refused, ends
 * the build ({@link SynologyFailures#endsBuild}), as for the optional
 * reads.
 */
final class NfsProbes {

	private NfsProbes() {}

	/** One probe ({@code SynologyApiClient.nfsSharePrivilege} in production). */
	interface Probe<J> {
		J load(String share) throws IOException, InterruptedException;
	}

	/** The number of NFS rules in a probe answer. */
	interface RuleCount<J> {
		int of(J rules);
	}

	/** What the probes found. */
	static final class Result<J> {
		/** Shares that are NFS exports (more than zero rules), with their rules. */
		final Map<String, J> rulesByShare = new HashMap<>();
		/** Shares whose probe failed this cycle, share name to reason. */
		final Map<String, String> failed = new LinkedHashMap<>();
	}

	/**
	 * Probes each share in order.
	 *
	 * @param shares the share names, in share-list order
	 * @param cp     the abort check run before each probe
	 * @param warn   receives one line per share whose probe failed
	 * @throws Exception an interrupt or abort, an unreachable NAS (connect
	 *         failure or timeout; wrapped, redacted text only) or a DSM
	 *         login refusal
	 */
	static <J> Result<J> run(List<String> shares, Probe<J> probe,
			RuleCount<J> count, SnapshotReads.Checkpoint cp,
			Consumer<String> warn) throws Exception {
		Result<J> r = new Result<>();
		for (String name : shares) {
			cp.check("NFS rule probe for share " + name);
			try {
				J rules = probe.load(name);
				if (count.of(rules) > 0) {
					r.rulesByShare.put(name, rules);
				}
			} catch (InterruptedException ie) {
				throw ie;
			} catch (Exception e) {
				// Build 31 (issue #14): a refused re-login fails the build;
				// probing on would send one failed login per share.
				if (SynologyFailures.authCode(e) != 0) {
					throw e;
				}
				// Build 31 (issue #13, review NIT-4): the NAS stopped
				// accepting connections or stopped answering. Every further
				// probe would wait out the same timeouts, so the build fails
				// here instead of probing each remaining share in turn.
				if (SynologyFailures.endsBuild(e)) {
					throw new IOException("NFS rule probe for share " + name
							+ " found the NAS unreachable: " + e.getMessage(),
							e);
				}
				r.failed.put(name, SynologyFailures.describe(e));
				warn.accept("Snapshot: NFS rule probe failed for share "
						+ name + ": " + e.getMessage());
			}
		}
		return r;
	}
}
