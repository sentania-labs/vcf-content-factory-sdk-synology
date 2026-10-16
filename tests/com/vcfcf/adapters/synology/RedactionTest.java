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
}
