package com.vcfcf.adapters.synology;

import java.io.IOException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import javax.net.ssl.SSLHandshakeException;

/**
 * Build 31 (issue #13): an unreachable NAS is DOWN, whatever wraps the
 * network failure; everything else is ERROR. The adapter's
 * mapCollectException returns DOWN exactly when
 * {@link SynologyFailures#isUnreachable} is true. SynologyApiClient never
 * chains the raw transport exception (DEF-001), so the class survives only
 * as {@link SynologyTransportException#kind()}, set by
 * {@link SynologyFailures#classifyTransport}. Run from the repo root
 * (ci/run_java_tests.sh).
 */
public final class FailureMappingTest {

	public static void main(String[] args) {
		transportClassification();
		unreachableIsDown();
		wrappedUnreachableIsDown();
		everythingElseIsError();
		unreachableOrRefusedEndsTheBuild();
		System.out.println("FailureMappingTest: all assertions passed");
	}

	/** What SynologyApiClient.callRaw does with a raw transport failure. */
	private static SynologyTransportException asClientThrows(Throwable raw) {
		return new SynologyTransportException("transport error calling "
				+ "SYNO.DSM.Info getinfo: " + raw.getMessage(),
				SynologyFailures.classifyTransport(raw));
	}

	static void transportClassification() {
		SynologyFailures.Transport C = SynologyFailures.Transport.CONNECT;
		SynologyFailures.Transport TO = SynologyFailures.Transport.TIMEOUT;
		SynologyFailures.Transport O = SynologyFailures.Transport.OTHER;
		T.eq(C, SynologyFailures.classifyTransport(
				new ConnectException("refused")), "refused");
		T.eq(C, SynologyFailures.classifyTransport(
				new HttpConnectTimeoutException("connect timed out")),
				"connect timeout is CONNECT, not TIMEOUT");
		T.eq(C, SynologyFailures.classifyTransport(
				new UnknownHostException("nas.invalid")), "unknown host");
		T.eq(C, SynologyFailures.classifyTransport(
				new NoRouteToHostException("no route")), "no route");
		T.eq(TO, SynologyFailures.classifyTransport(
				new HttpTimeoutException("request timed out")), "request timeout");
		T.eq(TO, SynologyFailures.classifyTransport(
				new SocketTimeoutException("Read timed out")), "read timeout");
		T.eq(C, SynologyFailures.classifyTransport(new IOException("send",
				new ConnectException("refused"))), "wrapped connect");
		T.eq(O, SynologyFailures.classifyTransport(
				new SSLHandshakeException("PKIX path building failed")), "TLS");
		T.eq(O, SynologyFailures.classifyTransport(
				new IOException("Connection reset")), "reset");
		T.eq(O, SynologyFailures.classifyTransport(null), "null");
	}

	static void unreachableIsDown() {
		Throwable[] down = {
			new ConnectException("Connection refused"),
			new HttpConnectTimeoutException("HTTP connect timed out"),
			new HttpTimeoutException("request timed out"),
			new SocketTimeoutException("Read timed out"),
			new UnknownHostException("nas.example.invalid"),
			new NoRouteToHostException("No route to host"),
		};
		for (Throwable t : down) {
			T.check(SynologyFailures.isUnreachable(t),
					t.getClass().getSimpleName() + " is DOWN");
			T.check(SynologyFailures.isUnreachable(asClientThrows(t)),
					t.getClass().getSimpleName() + " through the API client "
					+ "is DOWN");
		}
	}

	/** The old check saw only a bare ConnectException, which never arrived. */
	static void wrappedUnreachableIsDown() {
		// The cached failure the other resources of the cycle get: no cause
		// chain (review W3), the classification is carried as a field.
		Throwable cached = new SnapshotCache.CachedFailureException("cached",
				0, true);
		T.check(SynologyFailures.isUnreachable(cached), "cached failure is DOWN");
		T.check(!SynologyFailures.isUnreachable(
				new SnapshotCache.CachedFailureException("cached", 0, false)),
				"a cached failure that was not unreachable is ERROR");
		Throwable probe = new IOException("NFS rule probe for share x could "
				+ "not connect to the NAS",
				asClientThrows(new ConnectException("refused")));
		T.check(SynologyFailures.isUnreachable(probe),
				"the NFS probe stop is DOWN");
	}

	static void everythingElseIsError() {
		T.check(!SynologyFailures.isUnreachable(new SynologyDsmException(
				"Synology login failed: error code 400", 400, true)),
				"wrong password is ERROR");
		T.check(!SynologyFailures.isUnreachable(new SnapshotCache
				.CachedFailureException("cached", 400, false)),
				"cached wrong password is ERROR");
		T.check(!SynologyFailures.isUnreachable(new SnapshotCache
				.CachedFailureException("cached", 400, true)),
				"a cached login refusal is never DOWN");
		T.check(!SynologyFailures.isUnreachable(new IOException(
				"HTTP 502 from SYNO.DSM.Info getinfo")), "HTTP 502 is ERROR");
		T.check(!SynologyFailures.isUnreachable(new IOException("DSM.Info "
				+ "getinfo returned a 200 payload with no 'model' field")),
				"unreadable payload is ERROR");
		T.check(!SynologyFailures.isUnreachable(asClientThrows(
				new SSLHandshakeException("PKIX path building failed"))),
				"certificate failure is ERROR");
		T.check(!SynologyFailures.isUnreachable(new IllegalStateException("bug")),
				"unexpected exception is ERROR");
		T.check(!SynologyFailures.isUnreachable(null), "null is ERROR");
		IOException loop = new IOException("loop");
		RuntimeException outer = new RuntimeException("outer", loop);
		loop.initCause(outer);
		T.check(!SynologyFailures.isUnreachable(outer), "a cause cycle terminates");
	}

	/**
	 * What ends the build from an optional read, the UPS read or an NFS
	 * probe: an unreachable NAS (connect failure, and since review NIT-4 a
	 * timeout too) or a login refusal. Anything else skips that one read.
	 */
	static void unreachableOrRefusedEndsTheBuild() {
		T.check(SynologyFailures.endsBuild(asClientThrows(
				new ConnectException("refused"))), "refused stops the build");
		T.check(SynologyFailures.endsBuild(asClientThrows(
				new HttpConnectTimeoutException("connect timed out"))),
				"connect timeout stops the build");
		T.check(SynologyFailures.endsBuild(asClientThrows(
				new HttpTimeoutException("request timed out"))),
				"a read timeout stops the build (NIT-4)");
		T.check(SynologyFailures.endsBuild(new SynologyDsmException(
				"Synology login failed: error code 400", 400, true)),
				"a login refusal stops the build");
		T.check(!SynologyFailures.endsBuild(new SynologyDsmException(
				"SYNO.Core.FileServ.NFS.SharePrivilege load failed", 105, false)),
				"a DSM permission error does not stop the build");
		T.check(!SynologyFailures.endsBuild(asClientThrows(
				new SSLHandshakeException("PKIX path building failed"))),
				"a certificate failure on one read does not stop the build");
		T.check(!SynologyFailures.endsBuild(new IOException("HTTP 502")),
				"an HTTP error does not stop the build");
	}
}
