package com.vcfcf.adapters.synology;

/**
 * Secret redaction for anything that can reach the adapter log or a Test
 * Connection error (DEF-001, rules/no-secrets-on-disk.md). No VCF Ops SDK
 * and no framework-jar dependency, so the plain-main tests exercise exactly
 * the code the adapter runs. {@link SynologyApiClient} uses it, and so do
 * the Suite API WARN lines in {@link SynologyAdapter} and
 * {@link SynologyStitcher} (build 32, issue #27).
 *
 * <p>Build 32 (issue #17): moved here from {@code SynologyApiClient.redact}
 * (same patterns) and joined by {@link #transportFailure}, which the API
 * client now uses for runtime exceptions from the HTTP layer as well as for
 * {@code IOException}s.
 */
final class SynologyRedaction {

	private SynologyRedaction() {}

	/**
	 * Masks secret-bearing query parameters before a path, query string or
	 * exception message can reach the adapter log or a Test Connection
	 * error. The DSM entry.cgi URL carries the session id ({@code _sid}) on
	 * every authenticated call and, on the login call, the URL-encoded
	 * plaintext password and account ({@code passwd=}, {@code account=}).
	 * Build 32 (issue #27): also the Suite API shapes ({@code password=},
	 * {@code username=} and {@code user=} parameters, JSON {@code username}, {@code password} and {@code token}
	 * members, and an OpsToken, Bearer or Basic credential), for the
	 * Suite API exception messages the adapter logs.
	 * Each query parameter value is masked up to the next {@code &} or the
	 * end of the text,
	 * so a message that runs on after the URL loses its tail rather than
	 * leaking. The api/version/method portion is left intact so the message
	 * still identifies the failing endpoint.
	 *
	 * @return the masked text, or "" for {@code null}
	 */
	static String redact(String text) {
		if (text == null) return "";
		return text
				.replaceAll("(?i)(_sid=)[^&]*", "$1<redacted>")
				.replaceAll("(?i)(passwd=)[^&]*", "$1<redacted>")
				.replaceAll("(?i)(account=)[^&]*", "$1<redacted>")
				// Build 32 (issue #27): Suite API shapes. A form or query
				// password or user, a JSON username, password or token member, and
				// an OpsToken, Bearer or Basic credential.
				.replaceAll("(?i)(password=|username=|user=)[^&]*",
						"$1<redacted>")
				.replaceAll("(?i)(\"(?:username|password|token)\"\\s*:\\s*\")"
						+ "[^\"]*", "$1<redacted>")
				.replaceAll("(?i)((?:vRealizeOpsToken|OpsToken|Bearer|Basic)"
						+ "\\s+)[^\\s,;\"]+", "$1<redacted>");
	}

	/**
	 * Build 32 (issue #17): the exception {@code SynologyApiClient.callRaw}
	 * throws when the HTTP layer throws before any response. Built only from
	 * the endpoint label ({@code api + " " + method}, never the request
	 * path) and the redacted message, with no chained cause: a chained raw
	 * cause would hand its unredacted message (which can embed the request
	 * URI with {@code passwd=} or {@code _sid=}) to the framework logger.
	 * The failure's class is kept as {@link SynologyFailures.Transport}
	 * (issue #13), so an unreachable NAS still maps to DOWN. A runtime
	 * exception (an {@code IllegalArgumentException} from URI construction,
	 * an {@code UncheckedIOException}) is named by its simple class name;
	 * before build 32 it escaped callRaw with its raw message.
	 */
	static SynologyTransportException transportFailure(String endpoint,
			Throwable e) {
		String what = (e instanceof RuntimeException)
				? "request error calling " + endpoint + " ("
						+ e.getClass().getSimpleName() + "): "
				: "transport error calling " + endpoint + ": ";
		return new SynologyTransportException(
				what + redact(String.valueOf(e.getMessage())),
				SynologyFailures.classifyTransport(e));
	}
}
