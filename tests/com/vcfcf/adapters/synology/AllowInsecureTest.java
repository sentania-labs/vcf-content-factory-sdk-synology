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
		certificateReviewOfferedWhileSecure();
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

	/**
	 * Review W1: Validate Connection offers the NAS certificate for review
	 * exactly while validation is on, at the address the client connects
	 * to; nothing to review for an insecure or incomplete config.
	 */
	static void certificateReviewOfferedWhileSecure() {
		T.eq(java.util.List.of("https://nas.lab:5001/"),
				SynologyConfig.certificateCheckUrls("nas.lab", null, null),
				"absent port and insecure: 5001, reviewed");
		T.eq(java.util.List.of("https://nas.lab:5001/"),
				SynologyConfig.certificateCheckUrls(" nas.lab ", "", "false"),
				"host trimmed, blank port is 5001");
		T.eq(java.util.List.of("https://10.0.0.5:5551/"),
				SynologyConfig.certificateCheckUrls("10.0.0.5", "5551", "false"),
				"the configured port");
		T.eq(java.util.List.of("https://nas.lab:5001/"),
				SynologyConfig.certificateCheckUrls("nas.lab", "https", null),
				"a non-numeric port is the client's 5001");
		T.eq(java.util.List.of("https://nas.lab:5001/"),
				SynologyConfig.certificateCheckUrls("nas.lab", "5001", " true"),
				"' true' validates (strict parse), so it is reviewed");
		for (String v : new String[] {"true", "TRUE", "True"}) {
			T.eq(0, SynologyConfig.certificateCheckUrls("nas.lab", "5001", v)
					.size(), v + ": insecure, nothing to review");
		}
		T.eq(0, SynologyConfig.certificateCheckUrls(null, "5001", null).size(),
				"no host");
		T.eq(0, SynologyConfig.certificateCheckUrls("  ", "5001", null).size(),
				"blank host");
		T.eq(0, SynologyConfig.certificateCheckUrls("nas.lab", "70000", null)
				.size(), "port out of range");
		T.eq(0, SynologyConfig.certificateCheckUrls("nas.lab", "0", null)
				.size(), "port zero");
		for (String port : new String[] {null, "", "5551", "https"}) {
			T.eq(new SynologyConfig("nas.lab", port, "u", "p", null).baseUrl()
					+ "/", SynologyConfig.certificateCheckUrls("nas.lab", port,
							null).get(0),
					"the reviewed URL is the address the client connects to ("
					+ port + ")");
		}
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
