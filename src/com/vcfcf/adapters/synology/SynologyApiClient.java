package com.vcfcf.adapters.synology;

import com.vcfcf.adapter.http.ManagedHttpClient;
import com.vcfcf.adapter.json.SimpleJson;

import com.integrien.alive.common.adapter3.Logger;

import java.io.IOException;
import java.net.http.HttpResponse;

/**
 * Wraps the Synology DSM Web API. Every call but SYNO.API.Info is a POST to
 * /webapi/&lt;cgi&gt; (entry.cgi, or the path SYNO.API.Info names) with api,
 * version, method and parameters in an application/x-www-form-urlencoded
 * body. Session auth via {@code _sid} in that body (build 35, issue #16:
 * before, the login's account and password and every call's {@code _sid}
 * rode in the GET query string, where DSM's or a proxy's access log could
 * keep them; see {@link DsmRequest}).
 *
 * <p>v2: the logger is the framework instance logger supplied via
 * {@code VcfCfAdapter.componentLogger(SynologyApiClient.class)} — a
 * {@code com.integrien.alive.common.adapter3.Logger} wired to the adapter
 * instance's log file and pinned to INFO. Never use {@code java.util.logging}
 * (its records do not reach the adapter log) and never shadow the framework
 * {@code adapterLogger()}.
 *
 * FRAMEWORK NOTE: this client manages the session id itself and uses the
 * framework's {@link ManagedHttpClient#post} with no auth strategy. DSM also
 * accepts the session as the {@code id} cookie (checked live, build 35), but
 * the framework's cookie strategy (SessionCookieAuth) is client-wide and
 * re-authenticates on HTTP 401/403, while DSM reports an expired session as
 * error 106/107/119 in a 200 body, which {@link #call} already handles; the
 * {@code _sid} body parameter keeps login, re-login and logout in one place.
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
	/**
	 * Build 32 (issue #31): the CGI path and version per API, from
	 * SYNO.API.Info at the first login; see {@link DsmApis}.
	 */
	private final DsmApis apis = new DsmApis();
	/**
	 * Build 32 (issue #31, review N6): SYNO.API.Info answered (read, or
	 * failed for a reason other than an unreachable NAS) for this client,
	 * so it is not asked again; one client per configuration.
	 */
	private volatile boolean apiInfoAsked;

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
		// Build 32 (issue #31): learn the API versions first, once per
		// client (review N6: not again at each re-login while unread).
		if (!apiInfoAsked) {
			loadApiInfo();
		}
		// Build 35 (issue #16): a form POST, so the account and password
		// travel in the body, never in the URL.
		DsmRequest req = dsmRequest(DsmApis.AUTH, 7, "login",
				"account=" + urlEncode(username),
				"passwd=" + urlEncode(password),
				"session=" + urlEncode(sessionName),
				"format=sid");
		SimpleJson resp = callRaw(req, "SYNO.API.Auth login");
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

	/**
	 * Build 32 (issue #31): asks {@code SYNO.API.Info} (no session needed)
	 * which version and CGI path each API this adapter uses is offered at,
	 * so {@link #call} uses {@code min(maxVersion, desired)}. Never fails the
	 * login: any failure but an unreachable NAS leaves the built-in versions
	 * (build 31) in use, with one WARN, and is not asked again by this
	 * client (review N6; an edit builds a new client, which asks again). An
	 * unreachable NAS (connect failure, timeout) is rethrown, and the next
	 * login asks again:
	 * the login would fail the same way, and waiting out a second set of
	 * timeouts for it would double the cost of a down NAS.
	 */
	private void loadApiInfo() throws IOException, InterruptedException {
		DsmApis.Load found;
		try {
			found = apis.load(SynologyAdapter.SJ,
					callRaw(DsmRequest.get(DsmApis.INFO_PATH), "SYNO.API.Info query"));
		} catch (InterruptedException ie) {
			throw ie;
		} catch (Exception e) {
			if (SynologyFailures.isUnreachable(e)) {
				throw e;
			}
			apiInfoAsked = true;
			log.warn("SYNO.API.Info is not readable ("
					+ redact(SynologyFailures.describe(e)) + "); using the "
					+ "adapter's built-in DSM API versions (DSM 7.2 or later). "
					+ "Not asked again until the instance is edited.");
			return;
		}
		apiInfoAsked = true;
		if (!found.missingRequired.isEmpty()) {
			log.warn("This DSM does not offer the required API(s) "
					+ found.missingRequired + " (per SYNO.API.Info), so nothing "
					+ "can be collected from it. The adapter supports DSM 7.2 or "
					+ "later. They are still tried, so the NAS's own answer is "
					+ "what the collection error shows.");
		}
		// Review W4: an older API version may return an older payload that
		// lacks fields this adapter reads, so a lowered version is a WARN.
		if (!found.lowered.isEmpty()) {
			log.warn("This DSM offers only older versions of " + found.lowered
					+ " (per SYNO.API.Info); the adapter uses them, but values "
					+ "from these APIs are unverified: a field the older version "
					+ "does not return is skipped or, for some keys, reads 0. The "
					+ "adapter is tested on DSM 7.2 or later.");
		}
		log.info("SYNO.API.Info read: "
				+ (found.lowered.isEmpty() ? "every API at its desired version"
						: "older versions in use: " + found.lowered)
				+ (found.missingOptional.isEmpty() ? ""
						: "; not offered (optional): " + found.missingOptional));
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
			// _sid (DsmRequest.post adds it, in the body since build 35), so
			// only this client's session ends.
			callRaw(logoutHttp, DsmRequest.post(sid, apis.pick(DsmApis.AUTH, 7),
					DsmApis.AUTH, "logout", "session=" + urlEncode(sessionName)),
					"SYNO.API.Auth logout");
		} catch (InterruptedException ie) {
			Thread.currentThread().interrupt();
			log.warn("Logout interrupted (non-fatal)");
		} catch (Exception e) {
			// Defense in depth: the URL no longer carries _sid (build 35),
			// but the message is redacted before it reaches the log anyway.
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

	/**
	 * Core.Hardware.FanSpeed: the fan speed mode and which modes the model
	 * offers. Not fan health (build 35, review B2): see {@link #systemHealth}.
	 */
	public SimpleJson fanSpeed() throws IOException, InterruptedException {
		return call("SYNO.Core.Hardware.FanSpeed", 1, "get");
	}

	/**
	 * Build 35 (review B2): Core.System.SystemHealth get, DSM's System Health
	 * widget verdict (the one rule DSM ranks highest); see {@link SystemHealth}.
	 */
	public SimpleJson systemHealth() throws IOException, InterruptedException {
		return call("SYNO.Core.System.SystemHealth", 1, "get");
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
		SimpleJson resp = callRaw(dsmRequest(api, version, method, extra), endpoint);
		if (!resp.isSuccess()) {
			int code = (int) resp.get("error").get("code").asLong();
			if (code == 106 || code == 107 || code == 119) {
				log.info("Session expired (code=" + code + "), re-authenticating...");
				invalidateSession();
				login();
				resp = callRaw(dsmRequest(api, version, method, extra), endpoint);
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
	 * Issue a raw request: a form POST, or a GET for {@code SYNO.API.Info}.
	 * The {@code endpoint} label ({@code api + " " + method}) is the ONLY
	 * request context allowed into a thrown message — never the path or body.
	 *
	 * <p>DEF-001: the request body carries {@code _sid} on every
	 * authenticated call and the URL-encoded plaintext {@code passwd}/{@code account}
	 * on login (build 35, issue #16: in the body, no longer in the URL). The
	 * framework logs exception messages to the on-disk adapter log and surfaces
	 * them on Test-connection, so a message built from the request would land
	 * secrets on disk ({@code rules/no-secrets-on-disk.md}). The endpoint label
	 * identifies the failing API without any parameter.
	 */
	private SimpleJson callRaw(DsmRequest req, String endpoint)
			throws IOException, InterruptedException {
		return callRaw(http, req, endpoint);
	}

	private SimpleJson callRaw(ManagedHttpClient via, DsmRequest req,
			String endpoint) throws IOException, InterruptedException {
		HttpResponse<String> resp;
		try {
			resp = req.body == null
					? via.get(req.path, HttpResponse.BodyHandlers.ofString())
					: via.post(req.path, req.body, DsmRequest.FORM,
							HttpResponse.BodyHandlers.ofString());
		} catch (IOException | RuntimeException e) {
			// DEF-001: a transport failure (connect/SSL/timeout) can throw BEFORE
			// any response, and the underlying message may embed the request URI.
			// Since build 35 (issue #16) the URI carries no account=, passwd= or
			// _sid= (they are in the body), but the guard stays. Rethrow a standalone message built
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
	 * Mask secret-bearing parameters before a path, query string or form body
	 * can reach the adapter log or a Test-connection error. The DSM request
	 * body (the URL before build 35) carries
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

	/**
	 * Build 32 (issue #31): {@code version} is the desired version; the
	 * request carries {@code min(maxVersion, version)} on DSM's CGI path once
	 * SYNO.API.Info was read ({@link DsmApis#pick}). Build 35 (issue #16): a
	 * form POST with this client's {@code _sid} in the body.
	 */
	private DsmRequest dsmRequest(String api, int version, String method, String... extra) {
		return DsmRequest.post(session.sid(), apis.pick(api, version), api, method, extra);
	}

	private static String urlEncode(String s) {
		return DsmRequest.urlEncode(s);
	}
}
