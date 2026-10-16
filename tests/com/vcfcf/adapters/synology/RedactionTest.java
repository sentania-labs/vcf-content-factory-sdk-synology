package com.vcfcf.adapters.synology;

import java.io.UncheckedIOException;
import java.net.ConnectException;

/**
 * Build 32 (issue #17): a runtime exception thrown by the HTTP layer whose
 * message carries the secret-bearing request URI leaves callRaw as a
 * redacted, causeless exception, exactly as an IOException does. Run from
 * the repo root (ci/run_java_tests.sh).
 */
public final class RedactionTest {

	static final String LOGIN_URI = "/webapi/entry.cgi?api=SYNO.API.Auth"
			+ "&version=7&method=login&account=svc%2Dops&passwd=S3cr3t%21"
			+ "&session=VCFContentFactory-42&format=sid";
	static final String DATA_URI = "/webapi/entry.cgi?api=SYNO.DSM.Info"
			+ "&version=2&method=getinfo&_sid=abcSID123";

	public static void main(String[] args) {
		redactMasksEverySecret();
		runtimeExceptionIsRedactedAndCauseless();
		ioExceptionKeepsBuild31Shape();
		wrappedConnectFailureStillDown();
		suiteApiShapesAreMasked();
		frameworkPrincipalAndReviewShapesAreMasked();
		System.out.println("RedactionTest: all assertions passed");
	}

	static void noSecret(String s, String what) {
		T.check(!s.contains("S3cr3t"), what + ": password leaked: " + s);
		T.check(!s.contains("svc%2Dops"), what + ": account leaked: " + s);
		T.check(!s.contains("abcSID123"), what + ": sid leaked: " + s);
	}

	static void redactMasksEverySecret() {
		String r = SynologyRedaction.redact(LOGIN_URI + " " + DATA_URI);
		noSecret(r, "redact");
		T.check(r.contains("api=SYNO.API.Auth"), "endpoint kept: " + r);
		T.eq("", SynologyRedaction.redact(null), "null is empty");
	}

	static void runtimeExceptionIsRedactedAndCauseless() {
		IllegalArgumentException raw = new IllegalArgumentException(
				"Illegal character in query at index 90: https://nas:5001"
						+ LOGIN_URI);
		SynologyTransportException e = SynologyRedaction.transportFailure(
				"SYNO.API.Auth login", raw);
		noSecret(e.getMessage(), "runtime");
		T.check(e.getCause() == null, "runtime: no chained cause");
		T.check(e.getMessage().startsWith(
				"request error calling SYNO.API.Auth login "
						+ "(IllegalArgumentException): "),
				"runtime: endpoint label and class: " + e.getMessage());
		T.eq(SynologyFailures.Transport.OTHER, e.kind(), "runtime: kind");
		T.check(!SynologyFailures.isUnreachable(e), "runtime: ERROR, not DOWN");
		// Every link the framework logger could print is clean.
		noSecret(SynologyFailures.describe(e), "runtime describe");
	}

	static void ioExceptionKeepsBuild31Shape() {
		SynologyTransportException e = SynologyRedaction.transportFailure(
				"SYNO.DSM.Info getinfo",
				new java.io.IOException("reset while reading " + DATA_URI));
		noSecret(e.getMessage(), "io");
		T.check(e.getMessage().startsWith(
				"transport error calling SYNO.DSM.Info getinfo: "),
				"io: build 31 message shape: " + e.getMessage());
		T.check(e.getCause() == null, "io: no chained cause");
	}

	static void wrappedConnectFailureStillDown() {
		UncheckedIOException raw = new UncheckedIOException(
				"connect failed for " + DATA_URI,
				new ConnectException("Connection refused: " + DATA_URI));
		SynologyTransportException e = SynologyRedaction.transportFailure(
				"SYNO.DSM.Info getinfo", raw);
		noSecret(e.getMessage(), "unchecked");
		T.check(e.getCause() == null, "unchecked: no chained cause");
		T.eq(SynologyFailures.Transport.CONNECT, e.kind(), "unchecked: kind");
		T.check(SynologyFailures.isUnreachable(e), "unchecked: DOWN");
	}

	/** Build 32 (issue #27): what a Suite API exception message can carry. */
	static void suiteApiShapesAreMasked() {
		String raw = "POST https://ops.example/suite-api/api/auth/token/acquire"
				+ " failed: 401 body={\"username\":\"svc-ops\","
				+ "\"password\":\"Pa55word\"} token={\"token\":\"tok-777\"}"
				+ " header Authorization: vRealizeOpsToken tok-888, retry"
				+ " Basic c3ZjOnB3ZA== Bearer jwt.abc.def"
				+ " form username=svc-ops&password=Pa55word&x=1";
		String r = SynologyRedaction.redact(raw);
		for (String secret : new String[] {"svc-ops", "Pa55word", "tok-777",
				"tok-888", "c3ZjOnB3ZA==", "jwt.abc.def"}) {
			T.check(!r.contains(secret), "suite api: " + secret + " leaked: " + r);
		}
		T.check(r.contains("401"), "suite api: status kept: " + r);
		T.check(r.contains("/suite-api/api/auth/token/acquire"),
				"suite api: path kept: " + r);
	}

	/**
	 * Review B1 and N1 on build 32: the framework Suite API client's 401 and
	 * token messages end in principal=<user>; plus token=, authToken, URL
	 * userinfo, an escaped quote inside a JSON value, and user= alone.
	 */
	static void frameworkPrincipalAndReviewShapesAreMasked() {
		String fw401 = "Suite API GET https://ops.example/suite-api/api/resources"
				+ "?adapterKind=VMWARE returned 401, token expired or credential"
				+ " invalid mechanism=explicit principal=svc-vcfops";
		String r = SynologyRedaction.redact(fw401);
		T.check(!r.contains("svc-vcfops"), "401 principal leaked: " + r);
		T.check(r.contains("returned 401"), "401 status kept: " + r);
		String tok = SynologyRedaction.redact("Suite API token/acquire: no 'token' "
				+ "field in response mechanism=explicit principal=svc-vcfops");
		T.check(!tok.contains("svc-vcfops"), "token principal leaked: " + tok);
		String[][] cases = {
				{"GET /x?token=TOK999&a=1", "TOK999"},
				{"body {\"authToken\":\"AUTH999\"}", "AUTH999"},
				{"https://svc:PW999@ops.example/suite-api", "PW999"},
				{"https://svc:PW999@ops.example/suite-api", "svc:"},
				{"{\"password\":\"ab\\\"cdSECRET\"}", "cdSECRET"},
				{"form user=svc-only", "svc-only"},
		};
		for (String[] c : cases) {
			String m = SynologyRedaction.redact(c[0]);
			T.check(!m.contains(c[1]), c[1] + " leaked from <" + c[0] + ">: " + m);
		}
		T.check(SynologyRedaction.redact("https://ops.example/suite-api/api")
				.equals("https://ops.example/suite-api/api"), "a plain URL is kept");
	}
}
