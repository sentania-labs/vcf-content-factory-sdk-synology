package com.vcfcf.adapters.synology;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/**
 * Build 31: how a failed DSM read is classified. No VCF Ops SDK and no
 * framework-jar dependency, so the plain-main tests exercise it directly.
 *
 * <p>Every check walks the cause chain (cycle safe): the snapshot cache
 * wraps a cached failure, so the meaningful exception is rarely the
 * outermost one.
 */
final class SynologyFailures {

	private SynologyFailures() {}

	/**
	 * Issue #12: the DSM error code of the first authentication-class login
	 * refusal in {@code t}'s cause chain, or 0 when there is none. These are
	 * the {@code SYNO.API.Auth} login codes that a second login with the same
	 * settings would get again: 400 (no such account or wrong password), 401
	 * (account disabled), 402 (permission denied), 403 (2-step verification
	 * code required), 404 (2-step verification failed), 406 (2-step
	 * verification must be set up), 407 (source IP blocked by DSM Auto
	 * Block), 408, 409 and 410 (password expired or must be changed). Every
	 * failed login counts toward DSM Auto Block, which blocks the source IP
	 * (the collector, possibly shared with other integrations). Only a login
	 * refusal counts: other APIs reuse these numbers for their own errors.
	 */
	static int authCode(Throwable t) {
		Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
		for (Throwable c = t; c != null && seen.add(c); c = c.getCause()) {
			if (c instanceof SynologyDsmException) {
				SynologyDsmException d = (SynologyDsmException) c;
				if (d.isLogin() && isAuthCode(d.code())) {
					return d.code();
				}
			}
		}
		return 0;
	}

	/**
	 * Issue #13: whether {@code t} means the NAS could not be reached, so the
	 * resource is DOWN (the target is down) rather than ERROR (something is
	 * wrong with the adapter, its credentials or its data). True when the
	 * cause chain holds a {@link SynologyTransportException} whose original
	 * failure was {@link Transport#CONNECT} or {@link Transport#TIMEOUT}, or
	 * one of those network exceptions itself, and no authentication refusal:
	 * a NAS that refused the login was reached. Certificate failures,
	 * unreadable payloads and anything unexpected stay ERROR.
	 */
	static boolean isUnreachable(Throwable t) {
		if (authCode(t) != 0) return false;
		Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
		for (Throwable c = t; c != null && seen.add(c); c = c.getCause()) {
			Transport k = (c instanceof SynologyTransportException)
					? ((SynologyTransportException) c).kind()
					: classifyOne(c);
			if (k == Transport.CONNECT || k == Transport.TIMEOUT) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Issue #13: the NAS did not accept a connection at all (refused,
	 * connect timeout, unknown host, no route), found anywhere in the cause
	 * chain. Any further call in the same snapshot build would wait out the
	 * same timeouts, so the build stops instead of probing on.
	 */
	static boolean isConnectFailure(Throwable t) {
		Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
		for (Throwable c = t; c != null && seen.add(c); c = c.getCause()) {
			Transport k = (c instanceof SynologyTransportException)
					? ((SynologyTransportException) c).kind()
					: classifyOne(c);
			if (k == Transport.CONNECT) return true;
		}
		return false;
	}

	/** Issue #13: how a transport failure (no DSM answer) is classed. */
	enum Transport {
		/** No connection: refused, connect timeout, unknown host, no route. */
		CONNECT,
		/** Connected, but the request or a read timed out. */
		TIMEOUT,
		/** Anything else (TLS handshake, protocol, reset). */
		OTHER
	}

	/**
	 * Issue #13: the class of a raw transport exception, walking its cause
	 * chain (cycle safe). Used by {@link SynologyApiClient} before it drops
	 * the raw exception (DEF-001: the raw message can carry the request URL
	 * with {@code passwd=} or {@code _sid=}, so it is never chained).
	 */
	static Transport classifyTransport(Throwable t) {
		Transport found = Transport.OTHER;
		Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
		for (Throwable c = t; c != null && seen.add(c); c = c.getCause()) {
			Transport k = classifyOne(c);
			if (k == Transport.CONNECT) return k;
			if (k == Transport.TIMEOUT) found = k;
		}
		return found;
	}

	private static Transport classifyOne(Throwable c) {
		// HttpConnectTimeoutException extends HttpTimeoutException: test it first.
		if (c instanceof java.net.http.HttpConnectTimeoutException
				|| c instanceof java.net.ConnectException
				|| c instanceof java.net.UnknownHostException
				|| c instanceof java.net.NoRouteToHostException) {
			return Transport.CONNECT;
		}
		if (c instanceof java.net.http.HttpTimeoutException
				|| c instanceof java.net.SocketTimeoutException) {
			return Transport.TIMEOUT;
		}
		return Transport.OTHER;
	}

	/** Whether {@code code} is an authentication-class login refusal. */
	static boolean isAuthCode(int code) {
		switch (code) {
			case 400: case 401: case 402: case 403: case 404:
			case 406: case 407: case 408: case 409: case 410:
				return true;
			default:
				return false;
		}
	}

	/** Operator wording for a DSM login error code, or "" when unknown. */
	static String loginCodeMeaning(int code) {
		switch (code) {
			case 400: return "no such account or incorrect password";
			case 401: return "account disabled";
			case 402: return "permission denied";
			case 403: return "2-step verification code required";
			case 404: return "2-step verification failed";
			case 406: return "2-step verification must be set up for this account";
			case 407: return "source IP blocked by DSM Auto Block";
			case 408: return "password expired and cannot be changed";
			case 409: return "password expired";
			case 410: return "password must be changed";
			default: return "";
		}
	}

	/**
	 * One line for a log or a resource error: each distinct message in the
	 * cause chain, outermost first, joined with ": ". A link with no message
	 * shows its simple class name. Messages here are already redacted by
	 * {@link SynologyApiClient} (no URL, password or session id).
	 */
	static String describe(Throwable t) {
		if (t == null) return "unknown failure";
		StringBuilder sb = new StringBuilder();
		Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
		String last = null;
		for (Throwable c = t; c != null && seen.add(c); c = c.getCause()) {
			String m = c.getMessage();
			if (m == null || m.isEmpty()) m = c.getClass().getSimpleName();
			if (last != null && last.contains(m)) continue;
			if (sb.length() > 0) sb.append(": ");
			sb.append(m);
			last = m;
		}
		return sb.toString();
	}
}
