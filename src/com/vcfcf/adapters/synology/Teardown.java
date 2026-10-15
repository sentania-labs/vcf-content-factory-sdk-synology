package com.vcfcf.adapters.synology;

import java.util.function.Consumer;

/**
 * Build 31 (issue #21): runs the release steps of one configuration (DSM
 * logout, Suite API stitcher discard, HTTP client discard) best effort, in
 * the given order: every step runs even when an earlier one throws, and a
 * failure is one WARN naming the step, never an exception out of configure
 * or discard. No VCF Ops SDK and no framework-jar dependency, so the
 * plain-main tests drive it directly; the adapter supplies the steps.
 */
final class Teardown {

	private Teardown() {}

	/** One release step. */
	interface Step {
		void run() throws Exception;
	}

	/** A named step; a null action is skipped (nothing to release). */
	static final class Named {
		final String name;
		final Step action;

		Named(String name, Step action) {
			this.name = name;
			this.action = action;
		}
	}

	static Named step(String name, Step action) {
		return new Named(name, action);
	}

	/**
	 * @param why   why the configuration is released, for the WARN line
	 * @param warn  receives one line per failed step
	 * @param steps run in order; a step with a null action is skipped
	 * @return how many steps failed
	 */
	static int run(String why, Consumer<String> warn, Named... steps) {
		int failed = 0;
		for (Named s : steps) {
			if (s == null || s.action == null) continue;
			try {
				s.action.run();
			} catch (Exception e) {
				failed++;
				warn.accept("Releasing the previous Synology configuration ("
						+ why + "): " + s.name + " failed (non-fatal): "
						+ SynologyFailures.describe(e));
			}
		}
		return failed;
	}
}
