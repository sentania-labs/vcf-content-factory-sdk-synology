package com.vcfcf.adapters.synology;

/**
 * Build 31 (issue #15): only "true" turns certificate validation off, and
 * the configure WARN is present exactly while it is off. Before build 31
 * every value except "false" turned it off. Run from the repo root
 * (ci/run_java_tests.sh).
 */
public final class AllowInsecureTest {

	public static void main(String[] args) {
		strictParse();
		warningOnlyWhileInsecure();
		System.out.println("AllowInsecureTest: all assertions passed");
	}

	static void strictParse() {
		for (String v : new String[] {"true", "TRUE", "True"}) {
			T.check(SynologyConfig.parseAllowInsecure(v), v + " is insecure");
		}
		for (String v : new String[] {null, "", " ", "false", "FALSE", "no",
				"0", "yes", "1", "on", " true", "true ", "truee", "default"}) {
			T.check(!SynologyConfig.parseAllowInsecure(v),
					"<" + v + "> validates");
		}
		T.check(!new SynologyConfig("h", null, "u", "p", null).allowInsecure,
				"an absent identifier validates");
		T.check(!new SynologyConfig("h", null, "u", "p", "").allowInsecure,
				"a blank identifier validates");
		T.check(new SynologyConfig("h", null, "u", "p", "true").allowInsecure,
				"an explicit true keeps working after upgrade");
	}

	static void warningOnlyWhileInsecure() {
		SynologyConfig insecure = new SynologyConfig("nas.lab", "5551", "svc",
				"s3cret-pw", "true");
		String w = insecure.insecureWarning();
		T.check(w != null, "insecure mode warns");
		T.check(w.contains("nas.lab:5551"), "the warning names the address");
		T.check(!w.contains("s3cret-pw") && !w.contains("svc"),
				"the warning carries no credential");
		T.eq(null, new SynologyConfig("nas.lab", null, "svc", "pw", "false")
				.insecureWarning(), "validated mode does not warn");
		T.eq(null, new SynologyConfig("nas.lab", null, "svc", "pw", null)
				.insecureWarning(), "an absent value does not warn");
	}
}
