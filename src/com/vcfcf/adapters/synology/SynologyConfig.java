package com.vcfcf.adapters.synology;

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
		this.allowInsecure = !"false".equalsIgnoreCase(allowInsecure);
		this.suiteApiUrl = (suiteApiUrl != null) ? suiteApiUrl.trim() : "";
		this.suiteApiUser = (suiteApiUser != null) ? suiteApiUser.trim() : "";
		this.suiteApiPassword = (suiteApiPassword != null) ? suiteApiPassword : "";
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
