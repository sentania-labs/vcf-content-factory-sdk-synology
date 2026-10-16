package com.vcfcf.adapters.synology;

import java.util.HashSet;
import java.util.Set;

/**
 * Build 32 (issue #23): every collecting instance and every Test Connection
 * gets its own DSM session name, so one client's logout cannot end
 * another's session. Run from the repo root (ci/run_java_tests.sh).
 */
public final class SessionNameTest {

	public static void main(String[] args) {
		perInstanceName();
		testConnectionNameIsDistinct();
		randomFallbackAndTokens();
		System.out.println("SessionNameTest: all assertions passed");
	}

	static void perInstanceName() {
		T.eq("VCFContentFactory-42", SynologyConfig.sessionName(42, false, "x"),
				"instance name");
		T.check(!SynologyConfig.sessionName(42, false, "x")
				.equals(SynologyConfig.sessionName(43, false, "x")),
				"two instances differ");
		T.check(!"VCFContentFactory".equals(
				SynologyConfig.sessionName(42, false, "x")),
				"not the build 31 shared name");
	}

	static void testConnectionNameIsDistinct() {
		String t = SynologyConfig.sessionName(42, true, "ab12cd34");
		T.eq("VCFContentFactory-test-ab12cd34", t, "test name");
		T.check(!t.equals(SynologyConfig.sessionName(42, false, "ab12cd34")),
				"test differs from the instance with the same id");
	}

	static void randomFallbackAndTokens() {
		T.eq("VCFContentFactory-zz", SynologyConfig.sessionName(null, false, "zz"),
				"no instance id falls back to the random token");
		Set<String> seen = new HashSet<>();
		for (int i = 0; i < 200; i++) {
			String tok = SynologyConfig.randomToken();
			T.check(tok.matches("[0-9a-f]{8}"), "token shape: " + tok);
			seen.add(tok);
		}
		T.check(seen.size() > 190, "tokens are random: " + seen.size());
	}
}
