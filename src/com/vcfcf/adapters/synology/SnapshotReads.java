package com.vcfcf.adapters.synology;

import java.io.IOException;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Build 31 (issue #14): the DSM read plan of one snapshot build, and which
 * of its endpoints are required. No VCF Ops SDK and no framework-jar
 * dependency (generic over the JSON type), so the plain-main tests drive it
 * with a fake NAS; {@link SynologyApiClient} is the production source.
 *
 * <p>Before build 31 every endpoint was required except UPS: one optional
 * endpoint failing (FanSpeed on Virtual DSM or a model without fans, NFS or
 * iSCSI where the service is absent, CurrentConnection without admin
 * rights) failed the whole snapshot, and nothing at all was discovered,
 * with no hint which endpoint was the cause. Now only {@link #REQUIRED}
 * (DSM info, system info, utilization and storage load_info, which carry
 * the DiskStation, its pools, volumes, disks and SSD caches) fail the
 * build. Every other endpoint is optional: a failure stores the empty
 * value, records the endpoint and the reason in {@link Result#unread} so
 * the dependent metrics, objects and relationships are skipped, never
 * published as a sentinel, and logs one WARN. The WARN is logged once per
 * endpoint per configuration until the endpoint is readable again (then
 * one INFO), so a scoped account does not WARN every cycle.
 *
 * <p><b>Not installed is not unread (review W4).</b> The iSCSI LUN and
 * target lists answer DSM error 102 (the API does not exist) on a NAS
 * without iSCSI (SAN Manager not installed). That is a definite answer, no
 * LUNs and no targets, so it is stored as the empty value and counted as
 * read, with one INFO per configuration: Volume relationships keep
 * refreshing on such a NAS. Only these list endpoints get this, because for
 * them the empty value means "none"; for FanSpeed or the NFS service an
 * empty value would publish defaults, so 102 there stays unread.
 *
 * <p>Three failures of an optional read still fail the build: an
 * interrupt, a DSM login refusal (the session expired and the re-login was
 * refused; carrying on would send one more failed login per remaining
 * call, which counts toward DSM Auto Block) and an unreachable NAS, a
 * connect failure or (review NIT-4) a timeout (carrying on would wait out
 * the same three 30 s attempts per call). See
 * {@link SynologyFailures#endsBuild}.
 *
 * <p>The call order is unchanged from build 30.
 *
 * <p>One instance per configuration (it holds the once-per-endpoint WARN
 * state); thread safe.
 */
final class SnapshotReads {

	static final String DSM_INFO = "SYNO.DSM.Info";
	static final String SYSTEM_INFO = "SYNO.Core.System";
	static final String UTILIZATION = "SYNO.Core.System.Utilization";
	static final String STORAGE = "SYNO.Storage.CGI.Storage";
	static final String FAN = "SYNO.Core.Hardware.FanSpeed";
	static final String NFS_SERVICE = "SYNO.Core.FileServ.NFS";
	static final String LUNS = "SYNO.Core.ISCSI.LUN";
	static final String TARGETS = "SYNO.Core.ISCSI.Target";
	static final String NICS = "SYNO.Core.Network.Interface";
	static final String CONNECTIONS = "SYNO.Core.CurrentConnection";
	static final String SHARES = "SYNO.Core.Share";

	/** The endpoints whose failure fails the snapshot build. */
	static final Set<String> REQUIRED = Collections.unmodifiableSet(
			new HashSet<>(java.util.Arrays.asList(DSM_INFO, SYSTEM_INFO,
					UTILIZATION, STORAGE)));

	/** The label of the abort check before the login (review NIT-3). */
	static final String LOGIN = "SYNO.API.Auth login";

	/** The DSM calls of one build ({@link SynologyApiClient} in production). */
	interface Source<J> {
		/** Logs in when there is no session yet. */
		void ensureSession() throws IOException, InterruptedException;
		J dsmInfo() throws IOException, InterruptedException;
		J systemInfo() throws IOException, InterruptedException;
		J utilization() throws IOException, InterruptedException;
		J fanSpeed() throws IOException, InterruptedException;
		J nfsServiceGet() throws IOException, InterruptedException;
		J storageLoadInfo() throws IOException, InterruptedException;
		J iscsiLunList() throws IOException, InterruptedException;
		J iscsiTargetList() throws IOException, InterruptedException;
		J networkInterfaceList() throws IOException, InterruptedException;
		J currentConnections() throws IOException, InterruptedException;
		J shareList() throws IOException, InterruptedException;
	}

	/** Called before each DSM call (the adapter's abort check). */
	interface Checkpoint {
		void check(String next) throws InterruptedException,
				SnapshotCache.AbortedException;
	}

	private interface Call<J> {
		J call() throws IOException, InterruptedException;
	}

	/** The responses of one build; an unread optional endpoint holds the empty value. */
	static final class Result<J> {
		J dsmInfo;
		J systemInfo;
		J utilization;
		J fanSpeed;
		J nfsService;
		J storage;
		J lunList;
		J targets;
		J networkInterfaces;
		J connections;
		J shares;
		/** Optional endpoint to a one-line reason, for those not read. */
		final Map<String, String> unread = new LinkedHashMap<>();

		boolean isRead(String endpoint) {
			return !unread.containsKey(endpoint);
		}
	}

	/**
	 * Review W4: the optional list endpoints whose DSM error 102 (the API
	 * does not exist on this NAS) is read as an empty list.
	 */
	static final Set<String> EMPTY_WHEN_NOT_INSTALLED =
			Collections.unmodifiableSet(new HashSet<>(
					java.util.Arrays.asList(LUNS, TARGETS)));

	private final Set<String> warned = new HashSet<>();
	private final Set<String> notInstalled = new HashSet<>();

	/**
	 * Logs in if needed, then reads every endpoint once, in the build 30
	 * order. Review NIT-3: the abort check runs before the login too, so a
	 * Stop Collecting already requested sends nothing, not even a login
	 * that could wait out three 30 s attempts against a hung NAS.
	 *
	 * @param src   the DSM calls
	 * @param empty the value stored for an optional endpoint not read
	 * @param cp    the abort check run before each call
	 * @param warn  receives the one WARN per newly unreadable endpoint
	 * @param info  receives the one INFO per endpoint readable again
	 * @throws Exception a required endpoint's failure, or an interrupt, a
	 *         login refusal, a connect failure or a timeout on any endpoint
	 */
	<J> Result<J> read(Source<J> src, J empty, Checkpoint cp,
			Consumer<String> warn, Consumer<String> info) throws Exception {
		Result<J> r = new Result<>();
		cp.check(LOGIN);
		src.ensureSession();
		r.dsmInfo = get(DSM_INFO, src::dsmInfo, empty, r, cp, warn, info);
		r.systemInfo = get(SYSTEM_INFO, src::systemInfo, empty, r, cp, warn, info);
		r.utilization = get(UTILIZATION, src::utilization, empty, r, cp, warn, info);
		r.fanSpeed = get(FAN, src::fanSpeed, empty, r, cp, warn, info);
		r.nfsService = get(NFS_SERVICE, src::nfsServiceGet, empty, r, cp, warn, info);
		r.storage = get(STORAGE, src::storageLoadInfo, empty, r, cp, warn, info);
		r.lunList = get(LUNS, src::iscsiLunList, empty, r, cp, warn, info);
		r.targets = get(TARGETS, src::iscsiTargetList, empty, r, cp, warn, info);
		r.networkInterfaces = get(NICS, src::networkInterfaceList, empty, r, cp,
				warn, info);
		r.connections = get(CONNECTIONS, src::currentConnections, empty, r, cp,
				warn, info);
		r.shares = get(SHARES, src::shareList, empty, r, cp, warn, info);
		return r;
	}

	private <J> J get(String endpoint, Call<J> call, J empty, Result<J> r,
			Checkpoint cp, Consumer<String> warn, Consumer<String> info)
			throws Exception {
		cp.check(endpoint);
		if (REQUIRED.contains(endpoint)) {
			return call.call();
		}
		try {
			J v = call.call();
			boolean recovered;
			synchronized (this) {
				recovered = warned.remove(endpoint);
				notInstalled.remove(endpoint);
			}
			if (recovered) {
				info.accept("Optional DSM endpoint " + endpoint
						+ " is readable again; " + skipped(endpoint)
						+ " are collected again.");
			}
			return v;
		} catch (InterruptedException ie) {
			throw ie;
		} catch (Exception e) {
			if (SynologyFailures.endsBuild(e)) {
				throw e;
			}
			if (EMPTY_WHEN_NOT_INSTALLED.contains(endpoint)
					&& SynologyFailures.dataApiCode(e)
							== SynologyFailures.API_NOT_FOUND) {
				boolean first;
				synchronized (this) {
					warned.remove(endpoint);
					first = notInstalled.add(endpoint);
				}
				if (first) {
					info.accept("DSM endpoint " + endpoint + " does not exist "
							+ "on this NAS (DSM error 102: the package that "
							+ "provides it is not installed). Read as an empty "
							+ "list (no " + (LUNS.equals(endpoint) ? "iSCSI LUNs"
									: "iSCSI targets") + "); Volume relationships "
							+ "are still refreshed. Logged once until it exists "
							+ "again.");
				}
				return empty;
			}
			String reason = SynologyFailures.describe(e);
			r.unread.put(endpoint, reason);
			boolean first;
			synchronized (this) {
				first = warned.add(endpoint);
			}
			if (first) {
				warn.accept("Optional DSM endpoint " + endpoint + " is not "
						+ "readable: " + reason + ". Collection continues "
						+ "without it: " + skipped(endpoint) + " are skipped. "
						+ "The DSM account may lack the privilege, or the model "
						+ "or DSM edition may not offer it (see "
						+ "docs/installing.md). Logged once until it is "
						+ "readable again.");
			}
			return empty;
		}
	}

	/** Operator wording for what depends on an optional endpoint. */
	static String skipped(String endpoint) {
		switch (endpoint) {
			case FAN:
				return "the DiskStation Fan properties";
			case NFS_SERVICE:
				return "the DiskStation NFS service properties";
			case LUNS:
				return "iSCSI LUN objects, their metrics and their Volume and "
						+ "Datastore relationships";
			case TARGETS:
				return "the iSCSI LUN target properties";
			case NICS:
				return "the DiskStation NIC count and the NFS Datastore "
						+ "cross-link";
			case CONNECTIONS:
				return "the NFS client counts";
			case SHARES:
				return "NFS export objects, their metrics and their Volume and "
						+ "Datastore relationships";
			default:
				return "the metrics that depend on it";
		}
	}
}
