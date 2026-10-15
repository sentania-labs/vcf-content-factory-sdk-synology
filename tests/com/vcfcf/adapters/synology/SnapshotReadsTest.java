package com.vcfcf.adapters.synology;

import java.io.IOException;
import java.net.ConnectException;
import java.net.http.HttpTimeoutException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Build 31 (issue #14): only DSM info, system info, utilization and storage
 * load_info are required. An optional endpoint failing leaves the core
 * inventory (the DiskStation, pools, volumes, disks and SSD caches all come
 * from those four) in the snapshot, records the endpoint as not read and
 * WARNs once per configuration. Driven with a fake NAS. Run from the repo
 * root (ci/run_java_tests.sh).
 */
public final class SnapshotReadsTest {

	private static final String EMPTY = "<empty>";
	private static final List<String> ORDER = Arrays.asList(
			SnapshotReads.DSM_INFO, SnapshotReads.SYSTEM_INFO,
			SnapshotReads.UTILIZATION, SnapshotReads.FAN,
			SnapshotReads.NFS_SERVICE, SnapshotReads.STORAGE,
			SnapshotReads.LUNS, SnapshotReads.TARGETS, SnapshotReads.NICS,
			SnapshotReads.CONNECTIONS, SnapshotReads.SHARES);
	private static final List<String> OPTIONAL = Arrays.asList(
			SnapshotReads.FAN, SnapshotReads.NFS_SERVICE, SnapshotReads.LUNS,
			SnapshotReads.TARGETS, SnapshotReads.NICS,
			SnapshotReads.CONNECTIONS, SnapshotReads.SHARES);

	/** Fake NAS: each endpoint answers its name, or throws its failure. */
	static final class FakeNas implements SnapshotReads.Source<String> {
		final Map<String, Exception> failing = new HashMap<>();
		final List<String> calls = new ArrayList<>();
		int logins;

		@Override
		public void ensureSession() {
			logins++;
		}

		private String answer(String endpoint) throws IOException,
				InterruptedException {
			calls.add(endpoint);
			Exception e = failing.get(endpoint);
			if (e instanceof IOException) throw (IOException) e;
			if (e instanceof InterruptedException) throw (InterruptedException) e;
			if (e != null) throw new IllegalStateException(e);
			return endpoint;
		}

		@Override public String dsmInfo() throws IOException, InterruptedException { return answer(SnapshotReads.DSM_INFO); }
		@Override public String systemInfo() throws IOException, InterruptedException { return answer(SnapshotReads.SYSTEM_INFO); }
		@Override public String utilization() throws IOException, InterruptedException { return answer(SnapshotReads.UTILIZATION); }
		@Override public String fanSpeed() throws IOException, InterruptedException { return answer(SnapshotReads.FAN); }
		@Override public String nfsServiceGet() throws IOException, InterruptedException { return answer(SnapshotReads.NFS_SERVICE); }
		@Override public String storageLoadInfo() throws IOException, InterruptedException { return answer(SnapshotReads.STORAGE); }
		@Override public String iscsiLunList() throws IOException, InterruptedException { return answer(SnapshotReads.LUNS); }
		@Override public String iscsiTargetList() throws IOException, InterruptedException { return answer(SnapshotReads.TARGETS); }
		@Override public String networkInterfaceList() throws IOException, InterruptedException { return answer(SnapshotReads.NICS); }
		@Override public String currentConnections() throws IOException, InterruptedException { return answer(SnapshotReads.CONNECTIONS); }
		@Override public String shareList() throws IOException, InterruptedException { return answer(SnapshotReads.SHARES); }
	}

	public static void main(String[] args) throws Exception {
		fullPrivilegeReadsEverythingInTheSameOrder();
		optionalFailureLeavesCoreInventory();
		warnOncePerEndpointPerConfiguration();
		requiredFailureFailsTheBuild();
		loginRefusalAndConnectFailureStillFailTheBuild();
		checkpointRunsBeforeEveryCall();
		iscsiNotInstalledIsAnEmptyRead();
		transient102IsUnreadUntilConfirmed();
		System.out.println("SnapshotReadsTest: all assertions passed");
	}

	private static SnapshotReads.Result<String> read(SnapshotReads reads,
			FakeNas nas, List<String> warns, List<String> infos,
			List<String> checks) throws Exception {
		return reads.read(nas, EMPTY, checks::add, warns::add, infos::add);
	}

	/** Must not regress: full privileges, nothing skipped, no WARN. */
	static void fullPrivilegeReadsEverythingInTheSameOrder() throws Exception {
		FakeNas nas = new FakeNas();
		List<String> warns = new ArrayList<>();
		List<String> infos = new ArrayList<>();
		SnapshotReads.Result<String> r = read(new SnapshotReads(), nas, warns,
				infos, new ArrayList<>());
		T.eq(ORDER, nas.calls, "every endpoint once, in the build 30 order");
		T.check(r.unread.isEmpty(), "nothing unread: " + r.unread);
		T.eq(SnapshotReads.FAN, r.fanSpeed, "fan read");
		T.eq(SnapshotReads.SHARES, r.shares, "shares read");
		T.eq(0, warns.size(), "no WARN");
		T.eq(0, infos.size(), "no INFO");
	}

	/** The issue: one optional failure discovered nothing. */
	static void optionalFailureLeavesCoreInventory() throws Exception {
		for (String endpoint : OPTIONAL) {
			FakeNas nas = new FakeNas();
			nas.failing.put(endpoint, new IOException(endpoint
					+ " failed: {\"error\":{\"code\":105},\"success\":false}"));
			List<String> warns = new ArrayList<>();
			SnapshotReads.Result<String> r = read(new SnapshotReads(), nas,
					warns, new ArrayList<>(), new ArrayList<>());
			T.eq(SnapshotReads.DSM_INFO, r.dsmInfo, endpoint + ": DSM info kept");
			T.eq(SnapshotReads.SYSTEM_INFO, r.systemInfo,
					endpoint + ": system info kept");
			T.eq(SnapshotReads.UTILIZATION, r.utilization,
					endpoint + ": utilization kept");
			T.eq(SnapshotReads.STORAGE, r.storage,
					endpoint + ": storage (pools, volumes, disks, caches) kept");
			T.eq(new HashSet<>(Arrays.asList(endpoint)), r.unread.keySet(),
					endpoint + " is the only endpoint not read");
			T.check(!r.isRead(endpoint), endpoint + " reported not read");
			T.eq(ORDER.size(), nas.calls.size(),
					endpoint + ": the endpoints after it are still read");
			T.eq(1, warns.size(), endpoint + ": one WARN");
			T.check(warns.get(0).contains(endpoint)
					&& warns.get(0).contains("105"),
					"the WARN names the endpoint and the reason: " + warns.get(0));
		}
		// Every optional endpoint failing at once (a scoped account on a
		// Virtual DSM): the core inventory is still there.
		FakeNas nas = new FakeNas();
		for (String e : OPTIONAL) {
			nas.failing.put(e, new IOException(e + " failed"));
		}
		SnapshotReads.Result<String> r = read(new SnapshotReads(), nas,
				new ArrayList<>(), new ArrayList<>(), new ArrayList<>());
		T.eq(SnapshotReads.STORAGE, r.storage, "storage kept");
		T.eq(EMPTY, r.lunList, "an unread endpoint holds the empty value");
		T.eq(new HashSet<>(OPTIONAL), r.unread.keySet(), "all seven unread");
	}

	static void warnOncePerEndpointPerConfiguration() throws Exception {
		SnapshotReads reads = new SnapshotReads(); // one configuration
		FakeNas nas = new FakeNas();
		nas.failing.put(SnapshotReads.CONNECTIONS, new IOException("no admin"));
		List<String> warns = new ArrayList<>();
		List<String> infos = new ArrayList<>();
		for (int cycle = 0; cycle < 5; cycle++) {
			SnapshotReads.Result<String> r = read(reads, nas, warns, infos,
					new ArrayList<>());
			T.check(!r.isRead(SnapshotReads.CONNECTIONS),
					"unread in cycle " + cycle);
		}
		T.eq(1, warns.size(), "one WARN over five cycles");
		nas.failing.clear();
		read(reads, nas, warns, infos, new ArrayList<>());
		T.eq(1, infos.size(), "one INFO when it is readable again");
		read(reads, nas, warns, infos, new ArrayList<>());
		T.eq(1, infos.size(), "no INFO while it stays readable");
		nas.failing.put(SnapshotReads.CONNECTIONS, new IOException("no admin"));
		read(reads, nas, warns, infos, new ArrayList<>());
		T.eq(2, warns.size(), "WARNs again after it recovered and failed again");
		List<String> editedWarns = new ArrayList<>();
		read(new SnapshotReads(), nas, editedWarns, infos, new ArrayList<>());
		T.eq(1, editedWarns.size(), "an edited instance WARNs once afresh");
	}

	static void requiredFailureFailsTheBuild() throws Exception {
		for (String endpoint : SnapshotReads.REQUIRED) {
			FakeNas nas = new FakeNas();
			IOException boom = new IOException(endpoint + " failed");
			nas.failing.put(endpoint, boom);
			try {
				read(new SnapshotReads(), nas, new ArrayList<>(),
						new ArrayList<>(), new ArrayList<>());
				throw new AssertionError(endpoint + " must fail the build");
			} catch (IOException e) {
				T.check(e == boom, endpoint + " rethrown as is");
			}
		}
		T.eq(new HashSet<>(Arrays.asList(SnapshotReads.DSM_INFO,
				SnapshotReads.SYSTEM_INFO, SnapshotReads.UTILIZATION,
				SnapshotReads.STORAGE)), SnapshotReads.REQUIRED,
				"exactly the four required endpoints");
	}

	static void loginRefusalAndConnectFailureStillFailTheBuild()
			throws Exception {
		FakeNas nas = new FakeNas();
		nas.failing.put(SnapshotReads.FAN, new SynologyDsmException(
				"Synology login failed: error code 400", 400, true));
		try {
			read(new SnapshotReads(), nas, new ArrayList<>(), new ArrayList<>(),
					new ArrayList<>());
			throw new AssertionError("a refused re-login must fail the build");
		} catch (SynologyDsmException expected) {
			T.eq(SnapshotReads.FAN, nas.calls.get(nas.calls.size() - 1),
					"no call after the refused login");
		}
		nas = new FakeNas();
		nas.failing.put(SnapshotReads.LUNS, new SynologyTransportException(
				"transport error calling SYNO.Core.ISCSI.LUN list: refused",
				SynologyFailures.classifyTransport(new ConnectException("x"))));
		try {
			read(new SnapshotReads(), nas, new ArrayList<>(), new ArrayList<>(),
					new ArrayList<>());
			throw new AssertionError("a connect failure must fail the build");
		} catch (SynologyTransportException expected) {
			T.eq(SnapshotReads.LUNS, nas.calls.get(nas.calls.size() - 1),
					"no call after the connect failure");
		}
		// Review NIT-4: a read timeout on an optional endpoint ends the
		// build too (each further call would wait out the same ~93 s).
		nas = new FakeNas();
		nas.failing.put(SnapshotReads.FAN, new SynologyTransportException(
				"transport error calling SYNO.Core.Hardware.FanSpeed get: "
				+ "timed out", SynologyFailures.classifyTransport(
						new HttpTimeoutException("request timed out"))));
		try {
			read(new SnapshotReads(), nas, new ArrayList<>(), new ArrayList<>(),
					new ArrayList<>());
			throw new AssertionError("a timeout must end the build");
		} catch (SynologyTransportException expected) {
			T.eq(SnapshotReads.FAN, nas.calls.get(nas.calls.size() - 1),
					"no call after the timeout");
			T.check(SynologyFailures.isUnreachable(expected), "and it is DOWN");
		}
		// An interrupt is never swallowed.
		nas = new FakeNas();
		nas.failing.put(SnapshotReads.TARGETS, new InterruptedException("stop"));
		try {
			read(new SnapshotReads(), nas, new ArrayList<>(), new ArrayList<>(),
					new ArrayList<>());
			throw new AssertionError("interrupt expected");
		} catch (InterruptedException expected) {
			// expected
		}
	}

	/** What SynologyApiClient.call throws for a refused data API call. */
	private static SynologyDsmException dsm(String endpoint, int code) {
		return new SynologyDsmException(endpoint + " list failed: "
				+ "{\"error\":{\"code\":" + code + "},\"success\":false}",
				code, false);
	}

	/**
	 * Review W4: a NAS without iSCSI answers DSM error 102 (the API does
	 * not exist). That is "no LUNs, no targets", a read, so Volume
	 * relationships keep refreshing (SSD caches and NFS exports attach to
	 * their Volume) instead of freezing forever. Review NIT-R1: only from
	 * the second consecutive 102; the first is unread.
	 */
	static void iscsiNotInstalledIsAnEmptyRead() throws Exception {
		SnapshotReads reads = new SnapshotReads(); // one configuration
		FakeNas nas = new FakeNas();
		nas.failing.put(SnapshotReads.LUNS, dsm(SnapshotReads.LUNS, 102));
		nas.failing.put(SnapshotReads.TARGETS, dsm(SnapshotReads.TARGETS, 102));
		List<String> warns = new ArrayList<>();
		List<String> infos = new ArrayList<>();
		SnapshotReads.Result<String> f = read(reads, nas, warns, infos,
				new ArrayList<>());
		T.eq(new HashSet<>(Arrays.asList(SnapshotReads.LUNS,
				SnapshotReads.TARGETS)), f.unread.keySet(),
				"the first 102 is unread: " + f.unread);
		T.eq(0, warns.size() + infos.size(), "and logs nothing of its own");
		SnapshotReads.Result<String> r = read(reads, nas, warns, infos,
				new ArrayList<>());
		T.check(r.unread.isEmpty(), "the second 102 is not unread: " + r.unread);
		T.eq(EMPTY, r.lunList, "the LUN list is the empty list");
		T.eq(EMPTY, r.targets, "the target list is the empty list");
		T.check(SnapshotIsolation.volumeChildren(r.unread,
				java.util.Collections.emptyMap(),
				java.util.Collections.emptyMap()).allKnown(),
				"so Volume children are known and Volumes are parents");
		T.eq(0, warns.size(), "no WARN for a package that is not installed");
		T.eq(2, infos.size(), "one INFO per endpoint: " + infos);
		T.check(infos.get(0).contains("102")
				&& infos.get(0).contains(SnapshotReads.LUNS),
				"the INFO names the endpoint and the code: " + infos.get(0));
		read(reads, nas, warns, infos, new ArrayList<>());
		T.eq(2, infos.size(), "logged once per configuration");

		// Any other refusal of the LUN list (a missing privilege, 105) is
		// still unread: the permanent case docs/installing.md names.
		FakeNas scoped = new FakeNas();
		scoped.failing.put(SnapshotReads.LUNS, dsm(SnapshotReads.LUNS, 105));
		SnapshotReads.Result<String> s = read(new SnapshotReads(), scoped,
				new ArrayList<>(), new ArrayList<>(), new ArrayList<>());
		T.check(!s.isRead(SnapshotReads.LUNS), "105 on the LUN list is unread");
		T.check(!SnapshotIsolation.volumeChildren(s.unread,
				java.util.Collections.emptyMap(),
				java.util.Collections.emptyMap()).allKnown(), "and Volumes are frozen");

		// 102 on an endpoint whose empty value would publish defaults stays
		// unread (no Fan "unknown", no NFS "false").
		for (String e : new String[] {SnapshotReads.FAN,
				SnapshotReads.NFS_SERVICE, SnapshotReads.SHARES,
				SnapshotReads.NICS, SnapshotReads.CONNECTIONS}) {
			FakeNas other = new FakeNas();
			other.failing.put(e, dsm(e, 102));
			SnapshotReads.Result<String> o = read(new SnapshotReads(), other,
					new ArrayList<>(), new ArrayList<>(), new ArrayList<>());
			T.check(!o.isRead(e), e + ": 102 stays unread");
		}

		// A login refusal that happens to carry 102 is not "not installed".
		FakeNas login = new FakeNas();
		login.failing.put(SnapshotReads.LUNS, new SynologyDsmException(
				"Synology login failed: error code 102", 102, true));
		SnapshotReads.Result<String> l = read(new SnapshotReads(), login,
				new ArrayList<>(), new ArrayList<>(), new ArrayList<>());
		T.check(!l.isRead(SnapshotReads.LUNS), "a login 102 is unread");

		// Installed later: read normally, and a new 102 is logged again
		// once it is confirmed by a second one.
		nas.failing.clear();
		SnapshotReads.Result<String> after = read(reads, nas, warns, infos,
				new ArrayList<>());
		T.eq(SnapshotReads.LUNS, after.lunList, "read once it exists");
		nas.failing.put(SnapshotReads.LUNS, dsm(SnapshotReads.LUNS, 102));
		T.check(!read(reads, nas, warns, infos, new ArrayList<>())
				.isRead(SnapshotReads.LUNS), "a first 102 after it existed is unread");
		T.eq(2, infos.size(), "not logged yet");
		read(reads, nas, warns, infos, new ArrayList<>());
		T.eq(3, infos.size(), "a confirmed 102 after it existed is logged again");
	}

	/**
	 * Review NIT-R1: a transient 102 (SAN Manager still starting after a
	 * NAS reboot) never reads as "no LUNs": the existing LUNs are ERROR for
	 * the cycle and Volume edges are kept, and the run of 102s resets on
	 * a successful read and on any other failure.
	 */
	static void transient102IsUnreadUntilConfirmed() throws Exception {
		SnapshotReads reads = new SnapshotReads(); // one configuration
		FakeNas nas = new FakeNas();
		List<String> warns = new ArrayList<>();
		List<String> infos = new ArrayList<>();
		read(reads, nas, warns, infos, new ArrayList<>()); // LUNs present

		// Reboot: one 102, then SAN Manager is up again.
		nas.failing.put(SnapshotReads.LUNS, dsm(SnapshotReads.LUNS, 102));
		SnapshotReads.Result<String> r = read(reads, nas, warns, infos,
				new ArrayList<>());
		T.check(!r.isRead(SnapshotReads.LUNS), "a single 102 is unread");
		T.check(r.unread.get(SnapshotReads.LUNS).contains("102"),
				"the reason names the 102: " + r.unread);
		T.check(!SnapshotIsolation.volumeChildren(r.unread,
				java.util.Collections.emptyMap(),
				java.util.Collections.emptyMap()).allKnown(),
				"so Volume LUN edges are kept, not dropped");
		try {
			SnapshotIsolation.requireListRead(r.unread, "SynologyIscsiLun");
			throw new AssertionError("an existing LUN must be ERROR, not absent");
		} catch (IOException expected) {
			// expected
		}
		nas.failing.clear();
		T.check(read(reads, nas, warns, infos, new ArrayList<>())
				.isRead(SnapshotReads.LUNS), "read again after the reboot");

		// The successful read reset the count: the next 102 is a first one.
		nas.failing.put(SnapshotReads.LUNS, dsm(SnapshotReads.LUNS, 102));
		T.check(!read(reads, nas, warns, infos, new ArrayList<>())
				.isRead(SnapshotReads.LUNS), "102, read, 102: still unread");

		// Another failure in between also resets it.
		nas.failing.put(SnapshotReads.LUNS, dsm(SnapshotReads.LUNS, 105));
		T.check(!read(reads, nas, warns, infos, new ArrayList<>())
				.isRead(SnapshotReads.LUNS), "105 is unread");
		nas.failing.put(SnapshotReads.LUNS, dsm(SnapshotReads.LUNS, 102));
		T.check(!read(reads, nas, warns, infos, new ArrayList<>())
				.isRead(SnapshotReads.LUNS), "102, 105, 102: still unread");
		SnapshotReads.Result<String> c = read(reads, nas, warns, infos,
				new ArrayList<>());
		T.check(c.isRead(SnapshotReads.LUNS), "two in a row: not installed");
		T.eq(EMPTY, c.lunList, "read as the empty list");
		T.eq(1, infos.size(), "one INFO, for the confirmed 102 only: " + infos);
		T.eq(1, warns.size(), "one WARN, for the 105 only: " + warns);

		// Per configuration: an edited instance starts with no first 102.
		FakeNas fresh = new FakeNas();
		fresh.failing.put(SnapshotReads.LUNS, dsm(SnapshotReads.LUNS, 102));
		T.check(!read(new SnapshotReads(), fresh, new ArrayList<>(),
				new ArrayList<>(), new ArrayList<>()).isRead(SnapshotReads.LUNS),
				"a new configuration's first 102 is unread");

		// A build that ends before the LUN list leaves the count alone.
		SnapshotReads gap = new SnapshotReads();
		FakeNas g = new FakeNas();
		g.failing.put(SnapshotReads.LUNS, dsm(SnapshotReads.LUNS, 102));
		read(gap, g, new ArrayList<>(), new ArrayList<>(), new ArrayList<>());
		g.failing.put(SnapshotReads.FAN, new SynologyTransportException(
				"transport error calling SYNO.Core.Hardware.FanSpeed get: refused",
				SynologyFailures.classifyTransport(new ConnectException("x"))));
		try {
			read(gap, g, new ArrayList<>(), new ArrayList<>(), new ArrayList<>());
			throw new AssertionError("a connect failure ends the build");
		} catch (SynologyTransportException expected) {
			// expected
		}
		g.failing.remove(SnapshotReads.FAN);
		T.check(read(gap, g, new ArrayList<>(), new ArrayList<>(),
				new ArrayList<>()).isRead(SnapshotReads.LUNS),
				"the next 102 the endpoint answers confirms it");
	}

	static void checkpointRunsBeforeEveryCall() throws Exception {
		FakeNas nas = new FakeNas();
		List<String> checks = new ArrayList<>();
		read(new SnapshotReads(), nas, new ArrayList<>(), new ArrayList<>(),
				checks);
		List<String> withLogin = new ArrayList<>();
		withLogin.add(SnapshotReads.LOGIN);
		withLogin.addAll(ORDER);
		T.eq(withLogin, checks, "an abort check before the login and each call");
		T.eq(1, nas.logins, "one login per build");
		// Review NIT-3: Stop Collecting before the login sends nothing.
		FakeNas early = new FakeNas();
		try {
			new SnapshotReads().read(early, EMPTY, next -> {
				throw new SnapshotCache.AbortedException("Stop Collecting");
			}, w -> { }, i -> { });
			throw new AssertionError("abort expected");
		} catch (SnapshotCache.AbortedException expected) {
			T.eq(0, early.logins, "no login after Stop Collecting");
			T.eq(0, early.calls.size(), "no DSM call after Stop Collecting");
		}
		FakeNas stopped = new FakeNas();
		Set<String> seen = new HashSet<>();
		try {
			new SnapshotReads().read(stopped, EMPTY, next -> {
				seen.add(next);
				if (SnapshotReads.STORAGE.equals(next)) {
					throw new SnapshotCache.AbortedException("Stop Collecting");
				}
			}, w -> { }, i -> { });
			throw new AssertionError("abort expected");
		} catch (SnapshotCache.AbortedException expected) {
			T.check(!stopped.calls.contains(SnapshotReads.STORAGE),
					"no DSM call after Stop Collecting");
			T.eq(5, stopped.calls.size(), "the build stopped at the checkpoint");
		}
	}
}
