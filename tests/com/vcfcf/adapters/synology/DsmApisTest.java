package com.vcfcf.adapters.synology;

import static com.vcfcf.adapters.synology.TopologyTest.MAPS;
import static com.vcfcf.adapters.synology.TopologyTest.obj;

import java.util.Map;

/**
 * Build 32 (issue #31): SYNO.API.Info decides each API's version, as
 * min(maxVersion, desired), and its CGI path; until it is read, or for an
 * API it does not list, the build 31 versions on entry.cgi are used. The
 * DSM 7.3.2 fixture is the factory API map's
 * (knowledge/context/api-maps/synology-auth.md). Run from the repo root
 * (ci/run_java_tests.sh).
 */
public final class DsmApisTest {

	public static void main(String[] args) {
		unreadTableIsBuild31();
		dsm7KeepsDesiredVersions();
		olderDsmGetsItsMaxVersion();
		missingApisAreNamedAndStillTried();
		badResponsesLeaveTheTableUnread();
		pathsAreSanitised();
		System.out.println("DsmApisTest: all assertions passed");
	}

	static Map<String, Object> entry(String path, int min, int max) {
		return obj("path", path, "minVersion", min, "maxVersion", max);
	}

	/** Every API the adapter uses, at the given max versions. */
	static Map<String, Object> table(int authMax, int infoMax, int nfsMax) {
		Map<String, Object> data = obj();
		for (String api : DsmApis.DESIRED.keySet()) {
			data.put(api, entry("entry.cgi", 1, 1));
		}
		data.put(DsmApis.AUTH, entry("entry.cgi", 1, authMax));
		data.put(SnapshotReads.DSM_INFO, entry("entry.cgi", 1, infoMax));
		data.put(SnapshotReads.NFS_SERVICE, entry("entry.cgi", 1, nfsMax));
		data.put(SnapshotReads.SYSTEM_INFO, entry("entry.cgi", 1, 3));
		return obj("success", true, "data", data);
	}

	static void unreadTableIsBuild31() {
		DsmApis a = new DsmApis();
		T.check(!a.loaded(), "not loaded");
		T.eq("entry.cgi v7", a.pick(DsmApis.AUTH, 7).toString(), "auth as build 31");
		T.eq("entry.cgi v3", a.pick(SnapshotReads.NFS_SERVICE, 3).toString(),
				"nfs as build 31");
		T.check(DsmApis.INFO_PATH.startsWith("/webapi/query.cgi?api=SYNO.API.Info"
				+ "&version=1&method=query"), "info on query.cgi v1");
	}

	static void dsm7KeepsDesiredVersions() {
		DsmApis a = new DsmApis();
		Map<String, Object> resp = table(7, 2, 3);
		// The API map's verbatim DSM 7.3.2 entries.
		@SuppressWarnings("unchecked")
		Map<String, Object> data = (Map<String, Object>) resp.get("data");
		data.put("SYNO.API.Auth", obj("path", "entry.cgi", "minVersion", 1,
				"maxVersion", 7, "requestFormat", "JSON"));
		data.put("SYNO.Core.System.Utilization", entry("entry.cgi", 1, 1));
		DsmApis.Load l = a.load(MAPS, resp);
		T.check(a.loaded(), "loaded");
		T.check(l.lowered.isEmpty(), "nothing lowered: " + l.lowered);
		T.check(l.missingRequired.isEmpty() && l.missingOptional.isEmpty(), "nothing missing");
		T.eq("entry.cgi v7", a.pick(DsmApis.AUTH, 7).toString(), "auth v7");
		T.eq("entry.cgi v1", a.pick(SnapshotReads.UTILIZATION, 1).toString(),
				"utilization v1");
		// A DSM newer than the adapter: never above the desired version.
		DsmApis b = new DsmApis();
		b.load(MAPS, table(9, 5, 6));
		T.eq(7, b.pick(DsmApis.AUTH, 7).version, "capped at desired");
	}

	static void olderDsmGetsItsMaxVersion() {
		DsmApis a = new DsmApis();
		DsmApis.Load l = a.load(MAPS, table(6, 1, 2));
		T.eq(6, a.pick(DsmApis.AUTH, 7).version, "DSM 6.x: auth v6");
		T.eq(1, a.pick(SnapshotReads.DSM_INFO, 2).version, "dsm info v1");
		T.eq(2, a.pick(SnapshotReads.NFS_SERVICE, 3).version, "nfs v2");
		T.eq(3, l.lowered.size(), "three lowered: " + l.lowered);
		T.check(l.lowered.contains("SYNO.API.Auth v6 (desired v7)"), "named: " + l.lowered);
	}

	@SuppressWarnings("unchecked")
	static void missingApisAreNamedAndStillTried() {
		Map<String, Object> resp = table(7, 2, 3);
		Map<String, Object> data = (Map<String, Object>) resp.get("data");
		data.remove(SnapshotReads.STORAGE);
		data.remove(SnapshotReads.LUNS);
		DsmApis a = new DsmApis();
		DsmApis.Load l = a.load(MAPS, resp);
		T.eq(java.util.Arrays.asList(SnapshotReads.STORAGE), l.missingRequired,
				"required missing named");
		T.eq(java.util.Arrays.asList(SnapshotReads.LUNS), l.missingOptional,
				"optional missing named");
		T.eq("entry.cgi v1", a.pick(SnapshotReads.STORAGE, 1).toString(),
				"still tried the build 31 way");
		T.eq("entry.cgi v1", a.pick(SnapshotReads.LUNS, 1).toString(),
				"so a 102 from the NAS still drives the not-installed logic");
	}

	static void badResponsesLeaveTheTableUnread() {
		for (Object bad : new Object[] {obj("success", false, "error", obj("code", 103)),
				obj("success", true), null}) {
			DsmApis a = new DsmApis();
			try {
				a.load(MAPS, bad);
				throw new AssertionError("must throw for " + bad);
			} catch (IllegalStateException expected) {
				T.check(!a.loaded(), "unread after " + bad);
				T.eq("entry.cgi v7", a.pick(DsmApis.AUTH, 7).toString(), "fallback");
			}
		}
	}

	@SuppressWarnings("unchecked")
	static void pathsAreSanitised() {
		T.check(DsmApis.safeCgi("entry.cgi"), "entry.cgi");
		T.check(DsmApis.safeCgi("auth.cgi"), "auth.cgi");
		T.check(DsmApis.safeCgi("SurveillanceStation/camera.cgi"), "sub path");
		for (String bad : new String[] {"../x.cgi", "/etc/x.cgi", "entry.cgi?x=1",
				"entry.php", "", null, "a//b.cgi"}) {
			T.check(!DsmApis.safeCgi(bad), "rejected: " + bad);
		}
		Map<String, Object> resp = table(7, 2, 3);
		((Map<String, Object>) resp.get("data")).put(DsmApis.AUTH,
				entry("../../evil.cgi", 1, 7));
		DsmApis a = new DsmApis();
		a.load(MAPS, resp);
		T.eq("entry.cgi v7", a.pick(DsmApis.AUTH, 7).toString(), "unsafe path ignored");
	}
}
