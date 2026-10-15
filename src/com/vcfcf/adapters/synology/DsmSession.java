package com.vcfcf.adapters.synology;

import java.io.IOException;

/**
 * Build 31 (review W5): the DSM session id of one API client, and whether
 * the client is closed. No VCF Ops SDK and no framework-jar dependency, so
 * the plain-main tests drive it with fake login and logout calls;
 * {@link SynologyApiClient} supplies the real ones.
 *
 * <p><b>A closed client never logs in again.</b> {@link #close} (called when
 * the configuration is replaced or discarded) marks it closed, then logs
 * the session out. After that every login, including the re-login after a
 * DSM session expiry (106, 107, 119) and a first login, throws
 * {@link SynologyClientClosedException} without contacting the NAS. Before
 * build 31 review W5, a cycle still running on the old client after an
 * edit got 119 after the logout and logged in again: a session nobody
 * logged out (the issue #21 leak), or, after a password change, a refused
 * login with the old password that counted toward DSM Auto Block (the
 * issue #12 hazard). A login already in flight when the client is closed
 * has its new session logged straight out.
 *
 * <p><b>No orphan session (review NIT-R2).</b> The login's "closed yet?"
 * check and its store of the new session id run under one lock, and so do
 * {@link #close}'s "mark closed, take the session id" steps. So either the
 * login stores first and close takes and logs out that session, or close
 * runs first and the login sees it closed and logs its session out.
 * Before, a login could pass its check, close could then find no session
 * and log nothing out, and only then would the login store its id: one
 * session left for DSM to expire. The DSM calls themselves (login and
 * logout) run outside the lock.
 *
 * <p>The logout call itself is the client's to bound (a single attempt
 * with a short timeout); it must not throw.
 */
final class DsmSession {

	/** Logs in and returns the new session id. */
	interface Login {
		String login() throws IOException, InterruptedException;
	}

	/** Logs this session id out; best effort, never throws. */
	interface Logout {
		void logout(String sid);
	}

	private final Login loginCall;
	private final Logout logoutCall;
	private final Object lock = new Object();
	private volatile String sid;
	private volatile boolean closed;
	/**
	 * Test seam (review NIT-R2): run under the lock, between the login's
	 * closed check and its store. {@code null} in production.
	 */
	volatile Runnable beforeStore;

	DsmSession(Login loginCall, Logout logoutCall) {
		this.loginCall = loginCall;
		this.logoutCall = logoutCall;
	}

	/** The current session id, or {@code null}. */
	String sid() {
		return sid;
	}

	/** Whether {@link #close} was called. */
	boolean isClosed() {
		return closed;
	}

	/** Logs in, unless closed. */
	String login() throws IOException, InterruptedException {
		if (closed) throw new SynologyClientClosedException();
		String s = loginCall.login();
		synchronized (lock) {
			if (!closed) {
				Runnable hook = beforeStore;
				if (hook != null) hook.run();
				sid = s;
				return s;
			}
		}
		// Closed while this login was in flight: log the new session
		// straight out rather than leave it behind.
		logoutQuietly(s);
		throw new SynologyClientClosedException();
	}

	/** Logs in when there is no session; throws when closed. */
	void ensure() throws IOException, InterruptedException {
		if (closed) throw new SynologyClientClosedException();
		if (sid == null) login();
	}

	/** Forgets the session id without a logout (DSM said it expired). */
	void invalidate() {
		sid = null;
	}

	/** Logs the session out (if any) and forgets it; the client stays open. */
	void logout() {
		String s;
		synchronized (lock) {
			s = sid;
			sid = null;
		}
		if (s != null) logoutQuietly(s);
	}

	/** Closes the client for good: no login after this, then logout. */
	void close() {
		String s;
		synchronized (lock) {
			closed = true;
			s = sid;
			sid = null;
		}
		if (s != null) logoutQuietly(s);
	}

	private void logoutQuietly(String s) {
		try {
			logoutCall.logout(s);
		} catch (RuntimeException ignored) {
			// The contract says never throws; a bug here must not escape
			// configure or discard.
		}
	}
}
