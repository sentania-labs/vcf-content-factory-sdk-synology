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
