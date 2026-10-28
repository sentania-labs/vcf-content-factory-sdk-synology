package com.vcfcf.adapters.synology;

import java.nio.charset.StandardCharsets;

/**
 * Build 35 (issue #16): one DSM Web API request as a path plus an
 * {@code application/x-www-form-urlencoded} POST body. No VCF Ops SDK and
 * no framework-jar dependency, so the plain-main tests exercise exactly the
 * code {@link SynologyApiClient} runs.
 *
 * <p>Before build 35 every call was a GET with everything in the query
 * string: the login carried {@code account=} and {@code passwd=} and every
 * authenticated call carried {@code _sid=}, so credentials and session ids
 * could land in DSM's web server access log or a proxy's. Now the path is
 * only {@code /webapi/<cgi>}, and api, version, method, {@code _sid} and
 * every other parameter travel in the POST body, which DSM's
 * {@code entry.cgi} accepts (checked live on DSM 7.3.2, 2026-10-28: login,
 * logout and every API this adapter calls). The one GET left is
 * {@code SYNO.API.Info} ({@link #get}), which carries no secret and no
 * session.
 */
final class DsmRequest {

	static final String FORM = "application/x-www-form-urlencoded";

	/** Path relative to the base URL; never carries a parameter for a POST. */
	final String path;
	/** Form body, or {@code null} for a GET. */
	final String body;

	private DsmRequest(String path, String body) {
		this.path = path;
		this.body = body;
	}

	/** A GET of a fixed path that carries no secret ({@code SYNO.API.Info}). */
	static DsmRequest get(String path) {
		return new DsmRequest(path, null);
	}

	/**
	 * A POST to {@code /webapi/<pick.cgi>} with api, version, method,
	 * {@code _sid} (when {@code sid} is not null) and {@code extra} in the
	 * body. Each {@code extra} entry is {@code name=value} with the value
	 * already form-encoded (see {@link #urlEncode}).
	 */
	static DsmRequest post(String sid, DsmApis.Pick pick, String api,
			String method, String... extra) {
		StringBuilder sb = new StringBuilder("api=").append(urlEncode(api))
				.append("&version=").append(pick.version)
				.append("&method=").append(urlEncode(method));
		if (sid != null) {
			sb.append("&_sid=").append(urlEncode(sid));
		}
		for (String param : extra) {
			sb.append('&').append(param);
		}
		return new DsmRequest("/webapi/" + pick.cgi, sb.toString());
	}

	/** RFC 3986 unreserved characters kept, everything else percent-encoded as UTF-8. */
	static String urlEncode(String s) {
		if (s == null) return "";
		StringBuilder sb = new StringBuilder();
		for (byte b : s.getBytes(StandardCharsets.UTF_8)) {
			int c = b & 0xFF;
			if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
					|| (c >= '0' && c <= '9') || c == '-' || c == '_'
					|| c == '.' || c == '~') {
				sb.append((char) c);
			} else {
				sb.append(String.format("%%%02X", c));
			}
		}
		return sb.toString();
	}
}
