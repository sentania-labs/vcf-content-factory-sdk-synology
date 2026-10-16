package com.vcfcf.adapters.synology;

import com.vcfcf.adapter.http.ManagedHttpClient;
import com.vcfcf.adapter.json.SimpleJson;

import com.integrien.alive.common.adapter3.Logger;

import java.io.IOException;
import java.net.http.HttpResponse;

/**
 * Wraps the Synology DSM Web API. All calls go to /webapi/entry.cgi
 * differentiated by query parameters. Session auth via _sid query param.
 *
 * <p>v2: the logger is the framework instance logger supplied via
 * {@code VcfCfAdapter.componentLogger(SynologyApiClient.class)} — a
 * {@code com.integrien.alive.common.adapter3.Logger} wired to the adapter
 * instance's log file and pinned to INFO. Never use {@code java.util.logging}
 * (its records do not reach the adapter log) and never shadow the framework
 * {@code adapterLogger()}.
 *
 * FRAMEWORK NOTE: Synology uses _sid as a query parameter, not a cookie.
 * The framework's SessionCookieAuth adds a Cookie header, which DSM may
 * also accept, but the documented mechanism is the query param. This
 * client manages the session ID directly rather than using the framework's
 * auth strategies. This is a framework gap: a QueryParamAuth strategy
 * should be added for APIs that authenticate via query parameters.
 */
public final class SynologyApiClient implements SnapshotReads.Source<SimpleJson> {

	private final ManagedHttpClient http;
	/**
	 * Build 31 (review W5): the client the logout goes over. For a collect
	 * configuration a single-attempt client with a short timeout, so
	 * releasing an old configuration whose NAS is gone does not hold
	 * configure or discard for three 30 s attempts; the tester uses its own
	 * client for both.
	 */
	private final ManagedHttpClient logoutHttp;
	private final String username;
	private final String password;
	private final Logger log;
	/** Build 31 (review W5): session id and closed flag; see {@link DsmSession}. */
	private final DsmSession session;
	/**
	 * Build 32 (issue #23): this client's DSM session name, per instance (or
	 * per Test Connection); see {@link SynologyConfig#sessionName}.
	 */
	private final String sessionName;

	public SynologyApiClient(ManagedHttpClient http, String username,
			String password, String sessionName, Logger log) {
		this(http, http, username, password, sessionName, log);
	}

	public SynologyApiClient(ManagedHttpClient http, ManagedHttpClient logoutHttp,
			String username, String password, String sessionName, Logger log) {
		this.http = http;
		this.logoutHttp = logoutHttp;
		this.username = username;
		this.password = password;
		this.sessionName = sessionName;
		this.log = log;
		// Codex P2: a dedicated logout client is discarded on close (after
		// the logout attempt, with or without a session). When it is the
		// main client (the tester), the main client is not ours to discard.
		this.session = new DsmSession(this::sendLogin, this::sendLogout,
				logoutHttp != null && logoutHttp != http
						? logoutHttp::discard : null);
	}

	// --- Session management ---

	/**
	 * Logs in. Build 31 (review W5): throws
	 * {@link SynologyClientClosedException} without contacting the NAS once
	 * {@link #close} was called.
	 */
	public String login() throws IOException, InterruptedException {
		return session.login();
	}

	private String sendLogin() throws IOException, InterruptedException {
		String path = synoUrl("SYNO.API.Auth", 7, "login",
				"account=" + urlEncode(username),
				"passwd=" + urlEncode(password),
				"session=" + urlEncode(sessionName),
				"format=sid");
		SimpleJson resp = callRaw(path, "SYNO.API.Auth login");
		if (!resp.isSuccess()) {
			int code = (int) resp.get("error").get("code").asLong();
			// Build 31 (issue #12): typed, so an authentication-class refusal
			// (DSM error code in the body of a 200) is recognized by code, not
			// by message text, and is never retried within the cycle. No
			// account, password or session id in the message.
			String meaning = SynologyFailures.loginCodeMeaning(code);
			throw new SynologyDsmException("Synology login failed: error code "
					+ code + (meaning.isEmpty() ? "" : " (" + meaning + ")"),
					code, true);
		}
		String sid = resp.data().get("sid").asString();
		log.info("Synology login succeeded, session=" + sessionName);
		return sid;
	}

	/** Logs the session out (if any); the client stays usable. */
	public void logout() {
		session.logout();
	}

	/**
	 * Build 31 (review W5): closes the client for good (no login after
	 * this, so a cycle still running on it cannot re-login), then logs the
	 * session out over {@link #logoutHttp}, best effort, then discards
	 * {@link #logoutHttp} when it is a dedicated client (Codex P2), with or
	 * without a session and even when the logout failed. Never throws.
	 */
	public void close() {
		session.close();
	}

	private void sendLogout(String sid) {
		try {
			// Build 32 (issue #23): this client's own session name, and its
			// _sid (synoUrl adds it), so only this client's session ends.
			callRaw(logoutHttp, synoUrl(sid, "SYNO.API.Auth", 7, "logout",
					"session=" + urlEncode(sessionName)), "SYNO.API.Auth logout");
		} catch (InterruptedException ie) {
			Thread.currentThread().interrupt();
			log.warn("Logout interrupted (non-fatal)");
		} catch (Exception e) {
			// e.getMessage() may carry the request URL (with _sid) if the
			// underlying transport embeds it; redact before it reaches the log.
			log.warn("Logout failed (non-fatal): " + redact(String.valueOf(e.getMessage())));
		}
	}

	public void ensureSession() throws IOException, InterruptedException {
		session.ensure();
	}

	public void invalidateSession() {
		session.invalidate();
	}

	// --- API calls (each returns parsed JSON) ---

	/** DSM.Info: model, serial, temp, uptime, version */
	public SimpleJson dsmInfo() throws IOException, InterruptedException {
		return call("SYNO.DSM.Info", 2, "getinfo");
	}

	/** Core.System info: CPU details, firmware, NTP, timezone */
	public SimpleJson systemInfo() throws IOException, InterruptedException {
		return call("SYNO.Core.System", 3, "info");
	}

	/** Core.System.Utilization: CPU, memory, network, per-disk IO, per-LUN IO, NFS */
	public SimpleJson utilization() throws IOException, InterruptedException {
		return call("SYNO.Core.System.Utilization", 1, "get");
	}

	/** Storage.CGI.Storage load_info: pools, volumes, disks in one call */
	public SimpleJson storageLoadInfo() throws IOException, InterruptedException {
		return call("SYNO.Storage.CGI.Storage", 1, "load_info");
	}

	/** Core.ISCSI.LUN list: LUN metadata */
	public SimpleJson iscsiLunList() throws IOException, InterruptedException {
		return call("SYNO.Core.ISCSI.LUN", 1, "list");
	}

	/** Core.ISCSI.Target list: target IQN for ESXi correlation */
	public SimpleJson iscsiTargetList() throws IOException, InterruptedException {
		return call("SYNO.Core.ISCSI.Target", 1, "list");
	}

	/** Core.Hardware.FanSpeed: fan status */
	public SimpleJson fanSpeed() throws IOException, InterruptedException {
		return call("SYNO.Core.Hardware.FanSpeed", 1, "get");
	}

	/** Core.Network.Interface list: NIC inventory */
	public SimpleJson networkInterfaceList() throws IOException, InterruptedException {
		return call("SYNO.Core.Network.Interface", 1, "list");
	}

	/** Core.System.Status: crash flag, upgrade flag */
	public SimpleJson systemStatus() throws IOException, InterruptedException {
		return call("SYNO.Core.System.Status", 1, "get");
	}

	/** Core.FileServ.NFS get: NFS service config */
	public SimpleJson nfsServiceGet() throws IOException, InterruptedException {
		return call("SYNO.Core.FileServ.NFS", 3, "get");
	}

	/** Core.Share list: all shared folders with additional fields */
	public SimpleJson shareList() throws IOException, InterruptedException {
		String additional = "%5B%22hidden%22%2C%22encryption%22%2C%22share_quota%22%2C"
				+ "%22enable_share_cow%22%2C%22quota_value%22%2C%22enable_share_compress%22%5D";
		return call("SYNO.Core.Share", 1, "list",
				"shareType=all", "additional=" + additional);
	}

	/** NFS.SharePrivilege load: per-share NFS rules */
	public SimpleJson nfsSharePrivilege(String shareName) throws IOException, InterruptedException {
		return call("SYNO.Core.FileServ.NFS.SharePrivilege", 1, "load",
				"share_name=" + urlEncode(shareName));
	}

	/** Core.CurrentConnection get: active client list */
	public SimpleJson currentConnections() throws IOException, InterruptedException {
		return call("SYNO.Core.CurrentConnection", 1, "get");
	}

	/** Core.ExternalDevice.UPS get: UPS status */
	public SimpleJson upsGet() throws IOException, InterruptedException {
		return call("SYNO.Core.ExternalDevice.UPS", 1, "get");
	}

	// --- Internal helpers ---

	private SimpleJson call(String api, int version, String method, String... extra)
			throws IOException, InterruptedException {
		ensureSession();
		String endpoint = api + " " + method;
		SimpleJson resp = callRaw(synoUrl(api, version, method, extra), endpoint);
		if (!resp.isSuccess()) {
			int code = (int) resp.get("error").get("code").asLong();
			if (code == 106 || code == 107 || code == 119) {
				log.info("Session expired (code=" + code + "), re-authenticating...");
				invalidateSession();
				login();
				resp = callRaw(synoUrl(api, version, method, extra), endpoint);
			}
			if (!resp.isSuccess()) {
				// Build 31 (review W4): typed with the data API's DSM error
				// code (login=false, so it never counts as a login refusal),
				// so SnapshotReads can tell "this API does not exist on this
				// NAS" (102) from any other failure. Same message as before.
				throw new SynologyDsmException(api + " " + method + " failed: "
						+ resp.asString(),
						(int) resp.get("error").get("code").asLong(), false);
			}
		}
		return resp;
	}

	/**
	 * Issue a raw GET. The {@code endpoint} label ({@code api + " " + method}) is
	 * the ONLY request context allowed into a thrown message — never {@code path}.
	 *
	 * <p>DEF-001: the DSM {@code entry.cgi} URL carries {@code _sid} on every
	 * authenticated call and the URL-encoded plaintext {@code passwd}/{@code account}
	 * on login. The framework logs exception messages to the on-disk adapter log
	 * and surfaces them on Test-connection, so a message built from the path would
	 * land secrets on disk ({@code rules/no-secrets-on-disk.md}). The endpoint label
	 * identifies the failing API without any query string.
	 */
	private SimpleJson callRaw(String path, String endpoint)
			throws IOException, InterruptedException {
		return callRaw(http, path, endpoint);
	}

	private SimpleJson callRaw(ManagedHttpClient via, String path,
			String endpoint) throws IOException, InterruptedException {
		HttpResponse<String> resp;
		try {
			resp = via.get(path, HttpResponse.BodyHandlers.ofString());
		} catch (IOException | RuntimeException e) {
			// DEF-001: a transport failure (connect/SSL/timeout) can throw BEFORE
			// any response, and the underlying message may embed the request URI —
			// which on login carries account=/passwd=<plaintext> and on every
			// authenticated call carries _sid=. Rethrow a standalone message built
			// from the endpoint label only; redact any message text and DO NOT chain
			// the raw cause (a chained cause re-exposes getCause().getMessage() to
			// the framework logger). See rules/no-secrets-on-disk.md.
			// Build 31 (issue #13): keep the failure's class (connect, timeout,
			// other) as a field instead, so an unreachable NAS maps to DOWN and
			// a connect failure stops the snapshot build.
			// Build 32 (issue #17): runtime exceptions from the HTTP layer
			// (IllegalArgumentException from URI construction,
			// UncheckedIOException) get the same treatment; before they escaped
			// with their raw message. InterruptedException is not caught here.
			throw SynologyRedaction.transportFailure(endpoint, e);
		}
		if (resp.statusCode() != 200) {
			throw new IOException("HTTP " + resp.statusCode() + " from " + endpoint);
		}
		return SimpleJson.parse(resp.body());
	}

	/**
	 * Mask secret-bearing query parameters before a path/query string can reach
	 * the adapter log or a Test-connection error. The DSM entry.cgi URL carries
	 * the session id ({@code _sid}) on every authenticated call and, on the login
	 * call, the URL-encoded plaintext password and account ({@code passwd=},
	 * {@code account=}). Any of those must never land on disk
	 * ({@code rules/no-secrets-on-disk.md}). The api/version/method portion is
	 * left intact so the message still identifies the failing endpoint.
	 */
	static String redact(String path) {
		// Build 32 (issue #17): one implementation, SDK-free and unit tested.
		return SynologyRedaction.redact(path);
	}

	private String synoUrl(String api, int version, String method, String... extra) {
		return synoUrl(session.sid(), api, version, method, extra);
	}

	private static String synoUrl(String sid, String api, int version,
			String method, String... extra) {
		StringBuilder sb = new StringBuilder("/webapi/entry.cgi?api=");
		sb.append(api).append("&version=").append(version).append("&method=").append(method);
		if (sid != null) {
			sb.append("&_sid=").append(sid);
		}
		for (String param : extra) {
			sb.append("&").append(param);
		}
		return sb.toString();
	}

	private static String urlEncode(String s) {
		if (s == null) return "";
		StringBuilder sb = new StringBuilder();
		for (char c : s.toCharArray()) {
			if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') ||
					(c >= '0' && c <= '9') || c == '-' || c == '_' || c == '.' || c == '~') {
				sb.append(c);
			} else {
				for (byte b : String.valueOf(c).getBytes(java.nio.charset.StandardCharsets.UTF_8)) {
					sb.append(String.format("%%%02X", b & 0xFF));
				}
			}
		}
		return sb.toString();
	}
}
