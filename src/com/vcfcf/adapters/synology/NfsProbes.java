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
 *
 * <p>Build 32 (issue #20): the adapter skips the probes while the NFS
 * service is off ({@link #serviceOff}) and remembers answers across builds
 * ({@link Cached}).
 */
final class NfsProbes {

	private NfsProbes() {}

	/**
	 * Build 32 (issue #20): whether the snapshot skips the probes because
	 * the NFS service is off. Only a read NFS service response that carries
	 * {@code enable_nfs = false} counts; an unread response or a missing
	 * field is not proof, so the shares are probed as before.
	 *
	 * @param serviceRead whether {@code SYNO.Core.FileServ.NFS get} was read
	 * @param fieldMissing whether its {@code enable_nfs} member is absent
	 * @param enabled     its {@code enable_nfs} value
	 */
	static boolean serviceOff(boolean serviceRead, boolean fieldMissing,
			boolean enabled) {
		return serviceRead && !fieldMissing && !enabled;
	}

	/** One probe ({@code SynologyApiClient.nfsSharePrivilege} in production). */
	interface Probe<J> {
		J load(String share) throws IOException, InterruptedException;
	}

	/**
	 * The number of NFS rules in a probe answer, or a negative number when
	 * the answer carries no rule list (build 35 review: a success-shaped
	 * answer with no {@code rule} list is a failed probe, not zero rules).
	 */
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
	 * Probes each share in order, with no memory of earlier builds (every
	 * share probed, every failure WARNed). Build 31 behavior; the adapter
	 * uses a {@link Cached} instance instead (build 32, issue #20).
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
		return new Cached<J>(() -> 0L, 0L, 0L).run(shares, probe, count, cp, warn);
	}

	/**
	 * Build 32 (issue #20): the probes of one configuration, remembered
	 * across builds. Before build 32 every snapshot build probed every
	 * share (N DSM calls per cycle) and WARNed every failed probe every
	 * cycle. Now:
	 *
	 * <ul>
	 * <li>A share's answer (export with its rules, or not an export) is
	 *     reused until {@code refreshMs} has passed since the last full
	 *     refresh, then every share is probed again. A share not seen
	 *     before is probed at once, so a new export appears in the next
	 *     cycle. A share no longer listed is forgotten.</li>
	 * <li>A failed probe is not remembered: that share is probed again
	 *     next build (its export stays ERROR meanwhile, review B2). Its WARN
	 *     is logged at most once per {@code warnEveryMs} per share; the
	 *     failure itself is still recorded in {@link Result#failed} every
	 *     build.</li>
	 * <li>{@link #clear} forgets everything (the adapter calls it when the
	 *     NFS service is off, so turning it back on probes every share at
	 *     once).</li>
	 * </ul>
	 *
	 * Thread safe; one instance per configuration.
	 */
	static final class Cached<J> {
		private final java.util.function.LongSupplier clock;
		private final long refreshMs;
		private final long warnEveryMs;
		private final Map<String, J> exports = new HashMap<>();
		private final java.util.Set<String> nonExports = new java.util.HashSet<>();
		private final Map<String, Long> warnedAt = new HashMap<>();
		private long refreshedAt;
		private boolean everRefreshed;

		/**
		 * @param clock       milliseconds
		 * @param refreshMs   a share's remembered answer is reused for at
		 *                    most this long (0: never reused)
		 * @param warnEveryMs at most one WARN per share per this long (0:
		 *                    every failure)
		 */
		Cached(java.util.function.LongSupplier clock, long refreshMs,
				long warnEveryMs) {
			this.clock = clock;
			this.refreshMs = refreshMs;
			this.warnEveryMs = warnEveryMs;
		}

		/** Forgets every remembered answer and WARN time. */
		synchronized void clear() {
			exports.clear();
			nonExports.clear();
			warnedAt.clear();
			everRefreshed = false;
		}

		/** Same contract as {@link NfsProbes#run}, with the memory above. */
		synchronized Result<J> run(List<String> shares, Probe<J> probe,
				RuleCount<J> count, SnapshotReads.Checkpoint cp,
				Consumer<String> warn) throws Exception {
			long now = clock.getAsLong();
			boolean full = !everRefreshed || refreshMs <= 0
					|| now - refreshedAt >= refreshMs;
			Result<J> r = new Result<>();
			java.util.Set<String> listed = new java.util.HashSet<>(shares);
			exports.keySet().retainAll(listed);
			nonExports.retainAll(listed);
			warnedAt.keySet().retainAll(listed);
			for (String name : shares) {
				if (!full) {
					J known = exports.get(name);
					if (known != null) {
						r.rulesByShare.put(name, known);
						continue;
					}
					if (nonExports.contains(name)) continue;
				}
				cp.check("NFS rule probe for share " + name);
				try {
					J rules = probe.load(name);
					int n = count.of(rules);
					if (n < 0) {
						// Build 35: an answer with no rule list says nothing
						// about the share's rules; recorded as failed, never
						// folded into "not an export".
						exports.remove(name);
						nonExports.remove(name);
						failed(r, name, "answered with no rule list", now, warn);
						continue;
					}
					exports.remove(name);
					nonExports.remove(name);
					warnedAt.remove(name);
					if (n > 0) {
						r.rulesByShare.put(name, rules);
						exports.put(name, rules);
					} else {
						nonExports.add(name);
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
					// Not remembered: probed again next build.
					exports.remove(name);
					nonExports.remove(name);
					r.failed.put(name, SynologyFailures.describe(e));
					warnFailed(name, e.getMessage(), now, warn);
				}
			}
			if (full) {
				refreshedAt = now;
				everRefreshed = true;
			}
			return r;
		}

		/** A probe that answered without a rule list: failed, not remembered. */
		private void failed(Result<J> r, String name, String reason, long now,
				Consumer<String> warn) {
			r.failed.put(name, reason);
			warnFailed(name, reason, now, warn);
		}

		private void warnFailed(String name, String message, long now,
				Consumer<String> warn) {
			Long last = warnedAt.get(name);
			if (warnEveryMs <= 0 || last == null || now - last >= warnEveryMs) {
				warnedAt.put(name, now);
				warn.accept("Snapshot: NFS rule probe failed for share "
						+ name + ": " + message
						+ (warnEveryMs > 0 ? " (logged at most once every "
								+ (warnEveryMs / 60_000L) + " minutes per "
								+ "share while it keeps failing)" : ""));
			}
		}
	}
}
