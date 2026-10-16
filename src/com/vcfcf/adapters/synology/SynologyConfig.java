package com.vcfcf.adapters.synology;

import java.util.Collections;
import java.util.List;

public final class SynologyConfig {

	public final String host;
	public final int port;
	public final String username;
	public final String password;
	public final boolean allowInsecure;

	/**
	 * OPTIONAL explicit VCF Ops Suite API endpoint + credentials for the
	 * Datastore cross-link when this adapter instance runs on a remote collector
	 * / Cloud Proxy. Blank on a primary/analytics node — the adapter then uses
	 * the ambient localhost/maintenance-user path (primary-node behavior is
	 * byte-unchanged). See {@link #hasExplicitSuiteApi()} / {@link #suiteApiHost()}.
	 */
	public final String suiteApiUrl;
	public final String suiteApiUser;
	public final String suiteApiPassword;

	public SynologyConfig(String host, String port, String username,
			String password, String allowInsecure) {
		this(host, port, username, password, allowInsecure, null, null, null);
	}

	public SynologyConfig(String host, String port, String username,
			String password, String allowInsecure, String suiteApiUrl,
			String suiteApiUser, String suiteApiPassword) {
		this.host = (host != null && !host.isEmpty()) ? host : "localhost";
		int p = 5001;
		if (port != null && !port.isEmpty()) {
			try { p = Integer.parseInt(port); } catch (NumberFormatException ignored) {}
		}
		this.port = p;
		this.username = (username != null) ? username : "";
		this.password = (password != null) ? password : "";
		this.allowInsecure = parseAllowInsecure(allowInsecure);
		this.suiteApiUrl = (suiteApiUrl != null) ? suiteApiUrl.trim() : "";
		this.suiteApiUser = (suiteApiUser != null) ? suiteApiUser.trim() : "";
		this.suiteApiPassword = (suiteApiPassword != null) ? suiteApiPassword : "";
	}

	/**
	 * Build 31 (issue #15): only the exact value {@code "true"} (any letter
	 * case, no surrounding spaces) turns certificate validation off. Anything
	 * else, including {@code null}, blank, {@code "yes"} or {@code " true"},
	 * validates. Before build 31 the reverse held: everything except
	 * {@code "false"} skipped validation, so a blank value or a typo turned
	 * it off.
	 */
	static boolean parseAllowInsecure(String raw) {
		return "true".equalsIgnoreCase(raw);
	}

	/**
	 * Build 31 (review W1): the NAS endpoint VCF Operations offers for
	 * certificate review on Validate Connection, from the raw identifier
	 * values of the passed (possibly unsaved) config. Empty when Allow
	 * Insecure SSL is on, the host is blank, or the port the client would
	 * use is not 1 to 65535, so the platform check stays off and the
	 * connection test reports the real problem. The insecure test is
	 * {@link #parseAllowInsecure} on the untrimmed value, the same one the
	 * client uses: a stored {@code " true"} connects securely, so it must
	 * get the dialog. The port is the client's own parse (blank or not a
	 * number is 5001), so the reviewed address is the one the client
	 * connects to.
	 *
	 * @return {@code https://<host>:<port>/}, or empty
	 */
	static List<String> certificateCheckUrls(String host, String port,
			String allowInsecure) {
		if (parseAllowInsecure(allowInsecure)) return Collections.emptyList();
		if (host == null || host.trim().isEmpty()) {
			return Collections.emptyList();
		}
		int n = new SynologyConfig(host, port, null, null, allowInsecure).port;
		if (n < 1 || n > 65535) return Collections.emptyList();
		return Collections.singletonList("https://" + host.trim() + ":" + n
				+ "/");
	}

	/** Build 32 (issue #23): the prefix of every DSM session name this pak uses. */
	static final String SESSION_PREFIX = "VCFContentFactory";

	/**
	 * Build 32 (issue #23): the DSM session name of one API client. Before
	 * build 32 every instance and the Test Connection client used the one
	 * name {@code VCFContentFactory}; DSM groups sessions by name, so a
	 * logout from one client (Test Connection, an edit, a removal) could
	 * end the session another instance was still using against the same
	 * NAS and force re-logins. Now a collecting instance uses
	 * {@code VCFContentFactory-<adapterInstanceId>} (or
	 * {@code VCFContentFactory-<random>} when the platform gave no id), and
	 * Test Connection uses {@code VCFContentFactory-test-<random>}, distinct
	 * from every instance and from every other test.
	 *
	 * @param instanceId the adapter instance id, or {@code null}
	 * @param test       true for the Test Connection client
	 * @param random     a short random token (letters and digits)
	 */
	static String sessionName(Integer instanceId, boolean test, String random) {
		if (test) return SESSION_PREFIX + "-test-" + random;
		return SESSION_PREFIX + "-" + (instanceId != null
				? String.valueOf(instanceId) : random);
	}

	/** Build 32 (issue #23): an 8-character random token for {@link #sessionName}. */
	static String randomToken() {
		return java.util.UUID.randomUUID().toString().replace("-", "")
				.substring(0, 8);
	}

	/**
	 * Build 31 (issue #15): the WARN logged on every configure while insecure
	 * mode is on, or {@code null} when the NAS certificate is validated. No
	 * credential or session text, only the address.
	 */
	String insecureWarning() {
		if (!allowInsecure) return null;
		return "Allow Insecure SSL is true for " + host + ":" + port
				+ ": the NAS certificate is not validated, so the DSM "
				+ "credentials and session id go to whatever answers at that "
				+ "address. Set Allow Insecure SSL to false unless this is a lab "
				+ "NAS you accept that for.";
	}

	public String baseUrl() {
		return "https://" + host + ":" + port;
	}

	/**
	 * True iff explicit Suite API credentials are configured — both a username
	 * and a password are non-blank. This (not the URL, which carries a localhost
	 * default) is the gate that selects the explicit remote-collector path over
	 * the ambient localhost path. Blank ⇒ ambient ⇒ primary-node behavior is
	 * byte-unchanged.
	 */
	public boolean hasExplicitSuiteApi() {
		return !suiteApiUser.isEmpty() && !suiteApiPassword.isEmpty();
	}

	/**
	 * Bare host / FQDN of the primary/analytics Suite API node, parsed from
	 * {@link #suiteApiUrl}. The framework's {@code createExplicit(host, …)}
	 * rebuilds {@code https://<host>/suite-api} itself, so only the host is
	 * needed. Accepts a full URL ({@code https://vcf-ops.example.com/suite-api}),
	 * a bare host, or {@code host:port}; defaults to {@code localhost} when the
	 * URL is blank (operator error on a collector — surfaced in the startup log).
	 */
	public String suiteApiHost() {
		String u = suiteApiUrl;
		if (u == null || u.isEmpty()) {
			return "localhost";
		}
		try {
			java.net.URI uri = java.net.URI.create(u.contains("://") ? u : "https://" + u);
			String h = uri.getHost();
			if (h != null && !h.isEmpty()) {
				return h;
			}
		} catch (RuntimeException ignored) {
			// fall through to manual strip
		}
		String s = u.replaceFirst("^[a-zA-Z][a-zA-Z0-9+.-]*://", "");
		int slash = s.indexOf('/');
		if (slash >= 0) s = s.substring(0, slash);
		int colon = s.indexOf(':');
		if (colon >= 0) s = s.substring(0, colon);
		return s.isEmpty() ? "localhost" : s;
	}

	/**
	 * True iff the resolved {@link #suiteApiHost()} is a loopback address. This
	 * is the misconfiguration signal for the explicit path: a blank, malformed,
	 * or literally-localhost {@code vrops_url} all collapse to {@code localhost}
	 * (the safe degrade), which on a remote collector cannot reach the cluster's
	 * global VMWARE inventory and 403s. Resolved via
	 * {@link java.net.InetAddress#isLoopbackAddress()} (consistent with the
	 * framework's peer gating), not a bare {@code "localhost"} string match, so
	 * {@code 127.x} / {@code ::1} forms are caught too. An unresolvable host is
	 * not provably loopback — treated as loopback only when it is the literal
	 * default {@code localhost}, otherwise left to the explicit path to surface.
	 */
	public boolean suiteApiHostIsLoopback() {
		String h = suiteApiHost();
		try {
			return java.net.InetAddress.getByName(h).isLoopbackAddress();
		} catch (java.net.UnknownHostException e) {
			return "localhost".equalsIgnoreCase(h);
		}
	}
}
