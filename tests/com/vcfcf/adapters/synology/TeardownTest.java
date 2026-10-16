package com.vcfcf.adapters.synology;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Build 31 (issue #21): the release of a replaced or discarded
 * configuration runs every step in order (DSM logout before the HTTP
 * client it is sent over is discarded), survives a failing step with one
 * WARN, and skips what is absent. Run from the repo root
 * (ci/run_java_tests.sh).
 */
public final class TeardownTest {

	public static void main(String[] args) {
		everyStepRunsInOrder();
		aFailingStepDoesNotStopTheOthers();
		absentPartsAreSkipped();
		System.out.println("TeardownTest: all assertions passed");
	}

	static void everyStepRunsInOrder() {
		List<String> ran = new ArrayList<>();
		List<String> warns = new ArrayList<>();
		int failed = Teardown.run("instance reconfigured", warns::add,
				Teardown.step("DSM logout", () -> ran.add("logout")),
				Teardown.step("Suite API stitcher discard", () -> ran.add("stitcher")),
				Teardown.step("HTTP client discard", () -> ran.add("http")));
		T.eq(Arrays.asList("logout", "stitcher", "http"), ran,
				"logout first, the HTTP client last");
		T.eq(0, failed, "no failure");
		T.eq(0, warns.size(), "no WARN");
	}

	static void aFailingStepDoesNotStopTheOthers() {
		List<String> ran = new ArrayList<>();
		List<String> warns = new ArrayList<>();
		int failed = Teardown.run("instance reconfigured", warns::add,
				Teardown.step("DSM logout", () -> {
					throw new IOException("transport error calling SYNO.API.Auth "
							+ "logout: Connection refused");
				}),
				Teardown.step("Suite API stitcher discard", () -> {
					throw new IllegalStateException("token already revoked");
				}),
				Teardown.step("HTTP client discard", () -> ran.add("http")));
		T.eq(Arrays.asList("http"), ran, "the HTTP client is still discarded");
		T.eq(2, failed, "two failures counted");
		T.eq(2, warns.size(), "one WARN per failed step");
		T.check(warns.get(0).contains("DSM logout failed (non-fatal)")
				&& warns.get(0).contains("instance reconfigured"),
				"the WARN names the step and why: " + warns.get(0));
	}

	static void absentPartsAreSkipped() {
		List<String> warns = new ArrayList<>();
		int failed = Teardown.run("instance discarded", warns::add,
				Teardown.step("DSM logout", null),
				Teardown.step("Suite API stitcher discard", null),
				null);
		T.eq(0, failed, "nothing to release is not a failure");
		T.eq(0, warns.size(), "no WARN");
	}
}
