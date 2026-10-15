package com.vcfcf.adapters.synology;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Build 31 (review W5): a closed API client never logs in again, so a cycle
 * still running on the old client after an instance edit cannot leave a
 * session behind or send a refused login. Run from the repo root
 * (ci/run_java_tests.sh).
 */
public final class DsmSessionTest {

	/** Fake NAS: counts logins, records logouts. */
	static final class FakeNas {
		int logins;
		final List<String> logouts = new ArrayList<>();
		Runnable duringLogin = () -> { };

		String login() {
			logins++;
			duringLogin.run();
			return "sid" + logins;
		}
	}

	public static void main(String[] args) throws Exception {
		openClientLogsInOnceAndLogsOut();
		closedClientNeverLogsInAgain();
		closeDuringLoginLogsTheNewSessionOut();
		closedEndsTheBuildAsError();
		System.out.println("DsmSessionTest: all assertions passed");
	}

	private static DsmSession session(FakeNas nas) {
		return new DsmSession(nas::login, nas.logouts::add);
	}

	/** Must not regress: login once, re-login after expiry, plain logout. */
	static void openClientLogsInOnceAndLogsOut() throws Exception {
		FakeNas nas = new FakeNas();
		DsmSession s = session(nas);
		s.ensure();
		s.ensure();
		T.eq(1, nas.logins, "one login while the session holds");
		T.eq("sid1", s.sid(), "session kept");
		s.invalidate(); // DSM 119
		s.login();
		T.eq(2, nas.logins, "an open client re-logs in after expiry");
		s.logout();
		T.eq(List.of("sid2"), nas.logouts, "logout sends the live session");
		T.eq(null, s.sid(), "and forgets it");
		s.logout();
		T.eq(1, nas.logouts.size(), "nothing to log out twice");
		s.ensure();
		T.eq(3, nas.logins, "a plain logout does not close the client");
	}

	/** The W5 race: logout, then 119 on the old cycle, then a re-login. */
	static void closedClientNeverLogsInAgain() throws Exception {
		FakeNas nas = new FakeNas();
		DsmSession s = session(nas);
		s.ensure();
		s.close();
		T.check(s.isClosed(), "closed");
		T.eq(List.of("sid1"), nas.logouts, "close logs the session out");
		s.invalidate(); // the old cycle's next call gets 119
		for (int i = 0; i < 3; i++) {
			try {
				if (i == 0) s.login(); else s.ensure();
				throw new AssertionError("a closed client must not log in");
			} catch (SynologyClientClosedException expected) {
				// expected
			}
		}
		T.eq(1, nas.logins, "no login after close (no leak, no Auto Block count)");
		s.close();
		T.eq(1, nas.logouts.size(), "a second close sends nothing");
		DsmSession never = session(new FakeNas());
		never.close();
		try {
			never.ensure();
			throw new AssertionError("closed before its first login");
		} catch (SynologyClientClosedException expected) {
			// expected
		}
	}

	static void closeDuringLoginLogsTheNewSessionOut() throws Exception {
		FakeNas nas = new FakeNas();
		DsmSession s = session(nas);
		nas.duringLogin = s::close; // configure closes it mid-login
		try {
			s.ensure();
			throw new AssertionError("the in-flight login must not be kept");
		} catch (SynologyClientClosedException expected) {
			// expected
		}
		T.eq(List.of("sid1"), nas.logouts, "the new session is logged straight out");
		T.eq(null, s.sid(), "and not kept");
		DsmSession broken = new DsmSession(() -> "x", sid -> {
			throw new IllegalStateException("bug");
		});
		broken.ensure();
		broken.close(); // must not throw
		T.check(broken.isClosed(), "closed even when logout throws");
	}

	static void closedEndsTheBuildAsError() {
		IOException closed = new SynologyClientClosedException();
		T.check(SynologyFailures.endsBuild(closed),
				"an optional read on a closed client ends the build (no "
				+ "misleading 'optional endpoint not readable' WARN)");
		T.check(!SynologyFailures.isUnreachable(closed), "ERROR, not DOWN");
		T.eq(0, SynologyFailures.authCode(closed), "not a login refusal");
	}
}
