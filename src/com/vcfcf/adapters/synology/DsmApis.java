package com.vcfcf.adapters.synology;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Build 32 (issue #31): which CGI path and version each DSM Web API is
 * called with. No VCF Ops SDK and no framework-jar dependency (generic over
 * the JSON node type through {@link SynologyTopology.Json}), so the
 * plain-main tests exercise exactly the code {@link SynologyApiClient} runs.
 *
 * <p>Before build 32 every version and path was hardcoded and
 * {@code SYNO.API.Info} was never asked, so a DSM that does not offer a
 * version (DSM 6.x has no {@code SYNO.API.Auth} v7) failed with an unclear
 * error. Now the API client asks {@code SYNO.API.Info} once per
 * configuration, at its first login, and calls each API with
 * {@code min(maxVersion DSM offers, the version this adapter was written
 * for)} on the path DSM names. The hardcoded versions ({@link #DESIRED})
 * stay the desired values.
 *
 * <p>Fallbacks, so this can never cost a working login: while
 * {@code SYNO.API.Info} has not been read (it failed, or before the first
 * login), every API uses its desired version on {@code entry.cgi}, exactly
 * as build 31. An API {@code SYNO.API.Info} does not list is still called
 * the build 31 way (the NAS's own answer decides), and is named once in the
 * load summary, as a WARN for a required one.
 *
 * <p>Thread safe: the table is replaced in one volatile write.
 */
final class DsmApis {

	/** The default CGI (every API but SYNO.API.Info itself). */
	static final String ENTRY_CGI = "entry.cgi";

	/** The API that lists the others; always {@code query.cgi}, version 1. */
	static final String INFO_API = "SYNO.API.Info";
	static final String INFO_PATH = "/webapi/query.cgi?api=SYNO.API.Info"
			+ "&version=1&method=query&query=ALL";

	static final String AUTH = "SYNO.API.Auth";
	static final String NFS_PRIVILEGE = "SYNO.Core.FileServ.NFS.SharePrivilege";
	static final String UPS = "SYNO.Core.ExternalDevice.UPS";

	/** Every API this adapter calls, with the version it was written for. */
	static final Map<String, Integer> DESIRED;
	static {
		Map<String, Integer> m = new LinkedHashMap<>();
		m.put(AUTH, 7);
		m.put(SnapshotReads.DSM_INFO, 2);
		m.put(SnapshotReads.SYSTEM_INFO, 3);
		m.put(SnapshotReads.UTILIZATION, 1);
		m.put(SnapshotReads.STORAGE, 1);
		m.put(SnapshotReads.FAN, 1);
		m.put(SnapshotReads.NFS_SERVICE, 3);
		m.put(SnapshotReads.LUNS, 1);
		m.put(SnapshotReads.TARGETS, 1);
		m.put(SnapshotReads.NICS, 1);
		m.put(SnapshotReads.CONNECTIONS, 1);
		m.put(SnapshotReads.SHARES, 1);
		m.put(SnapshotReads.SYSTEM_HEALTH, 1);
		m.put(NFS_PRIVILEGE, 1);
		m.put(UPS, 1);
		DESIRED = Collections.unmodifiableMap(m);
	}

	/** The APIs without which nothing is collected: the login and the snapshot's required reads. */
	static boolean isRequired(String api) {
		return AUTH.equals(api) || SnapshotReads.REQUIRED.contains(api);
	}

	/** One API as {@code SYNO.API.Info} lists it. */
	static final class Entry {
		final String path;
		final int minVersion;
		final int maxVersion;

		Entry(String path, int minVersion, int maxVersion) {
			this.path = path;
			this.minVersion = minVersion;
			this.maxVersion = maxVersion;
		}
	}

	/** Where and how to call one API. */
	static final class Pick {
		final String cgi;
		final int version;

		Pick(String cgi, int version) {
			this.cgi = cgi;
			this.version = version;
		}

		@Override
		public String toString() {
			return cgi + " v" + version;
		}
	}

	/** What one load found, for the client's log lines. */
	static final class Load {
		/** Required APIs DSM does not list, or offers only above the desired version. */
		final List<String> missingRequired = new ArrayList<>();
		/** Optional APIs DSM does not list, or offers only above the desired version. */
		final List<String> missingOptional = new ArrayList<>();
		/** APIs called below their desired version, "api vN (desired vM)". */
		final List<String> lowered = new ArrayList<>();
	}

	/** API name to entry; {@code null} until a load succeeded. */
	private volatile Map<String, Entry> table;

	/** Whether {@code SYNO.API.Info} has been read for this client. */
	boolean loaded() {
		return table != null;
	}

	/**
	 * Reads a {@code SYNO.API.Info query} response. An unsuccessful or
	 * empty response leaves the table unread and throws, so the caller
	 * falls back.
	 */
	<J> Load load(SynologyTopology.Json<J> js, J response) {
		if (!js.bool(js.get(response, "success"))) {
			throw new IllegalStateException("SYNO.API.Info query was not "
					+ "successful");
		}
		J data = js.get(response, "data");
		if (data == null || js.isNull(data)) {
			throw new IllegalStateException("SYNO.API.Info query returned no data");
		}
		Map<String, Entry> t = new LinkedHashMap<>();
		Load out = new Load();
		for (Map.Entry<String, Integer> d : DESIRED.entrySet()) {
			J e = js.get(data, d.getKey());
			Double max = e == null ? null : SynologyMetrics.num(js, js.get(e, "maxVersion"));
			if (max == null) {
				(isRequired(d.getKey()) ? out.missingRequired : out.missingOptional)
						.add(d.getKey());
				continue;
			}
			Double min = SynologyMetrics.num(js, js.get(e, "minVersion"));
			String path = js.str(js.get(e, "path"));
			Entry entry = new Entry(safeCgi(path) ? path : ENTRY_CGI,
					min == null ? 1 : min.intValue(), max.intValue());
			int v = Math.min(entry.maxVersion, d.getValue());
			if (v < entry.minVersion) {
				// Review N2: DSM no longer offers any version this adapter
				// was written for. Counted as missing (named in the summary)
				// and called the build 31 way, so the NAS's answer decides.
				(isRequired(d.getKey()) ? out.missingRequired : out.missingOptional)
						.add(d.getKey() + " (offered from v" + entry.minVersion
								+ "; adapter written for v" + d.getValue() + ")");
				continue;
			}
			t.put(d.getKey(), entry);
			if (v < d.getValue()) {
				out.lowered.add(d.getKey() + " v" + v + " (desired v" + d.getValue() + ")");
			}
		}
		table = t;
		return out;
	}

	/**
	 * Whether a path from {@code SYNO.API.Info} is used as is: a plain CGI
	 * name under {@code /webapi/} ({@code entry.cgi}, {@code auth.cgi},
	 * {@code SurveillanceStation/x.cgi}); anything else uses
	 * {@code entry.cgi}.
	 */
	static boolean safeCgi(String path) {
		return path != null && path.matches("[A-Za-z0-9_\\-]+(/[A-Za-z0-9_\\-]+)*\\.cgi");
	}

	/**
	 * Where and how to call {@code api}: {@code min(maxVersion, desired)}
	 * on DSM's path when {@code SYNO.API.Info} lists it, else the desired
	 * version on {@code entry.cgi} (build 31).
	 */
	Pick pick(String api, int desired) {
		Map<String, Entry> t = table;
		Entry e = t == null ? null : t.get(api);
		if (e == null) return new Pick(ENTRY_CGI, desired);
		return new Pick(e.path, Math.min(e.maxVersion, desired));
	}
}
