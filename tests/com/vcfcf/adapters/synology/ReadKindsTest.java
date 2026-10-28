package com.vcfcf.adapters.synology;

import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Build 35 (buildkit 1.0.22): what the adapter declares read in full each
 * cycle ({@link ReadKinds}), which is all the framework counts misses for.
 * The counting and the 12-cycle mark are the framework's
 * (VanishedResourceTest in the factory); {@link Model} drives a model of
 * that rule over the declared kinds, the same order of operations as
 * {@code VcfCfAdapter.markVanished}, so the pack-side cases read end to end.
 * The devel fixture is the platform's Synology inventory on devel_9_1 and
 * devel_8_18 (read-only GET, 2026-10-28): 29 objects from before build 34
 * with no {@code diskstation_id}. Run from the repo root
 * (ci/run_java_tests.sh).
 */
public final class ReadKindsTest {

	public static void main(String[] args) {
		completeReadNamesEveryKindButTheWorld();
		completeReadCountsMisses();
		upsReadFailureMarksNoUps();
		upsAnswerWithoutConnectFieldMarksNoUps();
		connectedUpsWithoutModelMarksNoUps();
		disconnectedUpsIsCountedGone();
		ssdCacheReadFailureMarksNoCache();
		iscsiReadFailureMarksNoLun();
		systemHealthReadFailureMarksNoDiskStation();
		nfsProbeFailureMarksNoExport();
		shareListUnreadMarksNoExport();
		nfsServiceOffMarksNoExport();
		incompleteLoadInfoMarksNoPoolVolumeOrDisk();
		worldIsExcluded();
		System.out.println("ReadKindsTest: all assertions passed");
	}

	/** A healthy devel-shaped cycle: every read succeeded, a UPS connected. */
	private static ReadKinds complete() {
		ReadKinds r = new ReadKinds();
		r.systemHealth(true);
		r.storage(true, true, true, 7, 0);
		r.ssdCaches(true, 0);
		r.luns(true, true, 0);
		r.exports(true, true, 0, false, 0);
		r.ups(true, true, true, true);
		return r;
	}

	private static Set<String> set(String... kinds) {
		return new LinkedHashSet<>(Arrays.asList(kinds));
	}

	private static final Set<String> STORAGE = set(ReadKinds.POOL,
			ReadKinds.VOLUME, ReadKinds.DISK);

	static void completeReadNamesEveryKindButTheWorld() {
		T.eq(set(ReadKinds.DISKSTATION, ReadKinds.POOL, ReadKinds.VOLUME,
				ReadKinds.DISK, ReadKinds.CACHE, ReadKinds.LUN,
				ReadKinds.EXPORT, ReadKinds.UPS), complete().kinds(),
				"a complete read names every kind but the world");
	}

	/**
	 * The framework's rule, modelled: per cycle, a known object of a
	 * declared, non-excluded kind that the enumeration did not deliver
	 * takes a miss; a delivered one is reset; at the threshold it is marked.
	 * A kind not declared this cycle neither counts nor resets.
	 */
	private static final class Model {
		final Map<String, Integer> misses = new HashMap<>();
		final Set<String> marked = new LinkedHashSet<>();

		void cycle(Map<String, String> known, Set<String> delivered,
				Set<String> declared) {
			Set<String> read = new LinkedHashSet<>();
			for (String k : declared) read.add(k.toLowerCase());
			for (Map.Entry<String, String> e : known.entrySet()) {
				String key = e.getKey();
				String kind = e.getValue().toLowerCase();
				if (!read.contains(kind)) continue;
				if (ReadKinds.EXCLUDED.stream()
						.anyMatch(x -> x.equalsIgnoreCase(kind))) continue;
				if (delivered.contains(key)) {
					misses.remove(key);
					continue;
				}
				if (marked.contains(key)) continue;
				int n = misses.merge(key, 1, Integer::sum);
				if (n >= 12) marked.add(key);
			}
		}
	}

	/** One object per kind on the current key, plus one legacy of each. */
	private static Map<String, String> devel() {
		Map<String, String> known = new HashMap<>();
		known.put("world:serial", ReadKinds.WORLD);
		known.put("world:legacy", ReadKinds.WORLD);
		known.put("ds:serial", ReadKinds.DISKSTATION);
		for (String kind : new String[] {ReadKinds.POOL, ReadKinds.VOLUME,
				ReadKinds.DISK, ReadKinds.CACHE, ReadKinds.LUN,
				ReadKinds.EXPORT}) {
			known.put(kind + ":serial", kind);
			known.put(kind + ":legacy", kind);
		}
		known.put("ups:serial", ReadKinds.UPS);
		return known;
	}

	private static Set<String> delivered() {
		Set<String> d = set("world:serial", "ds:serial", "ups:serial");
		for (String kind : new String[] {ReadKinds.POOL, ReadKinds.VOLUME,
				ReadKinds.DISK, ReadKinds.CACHE, ReadKinds.LUN,
				ReadKinds.EXPORT}) {
			d.add(kind + ":serial");
		}
		return d;
	}

	private static final Set<String> LEGACY_MARKED = set(
			ReadKinds.POOL + ":legacy", ReadKinds.VOLUME + ":legacy",
			ReadKinds.DISK + ":legacy", ReadKinds.CACHE + ":legacy",
			ReadKinds.LUN + ":legacy", ReadKinds.EXPORT + ":legacy");

	/**
	 * A complete read: every pre-build-34 orphan takes a miss and is marked
	 * at the 12th cycle; the legacy World is excluded and stays.
	 */
	static void completeReadCountsMisses() {
		Model m = new Model();
		for (int i = 1; i <= 11; i++) {
			m.cycle(devel(), delivered(), complete().kinds());
		}
		for (String k : LEGACY_MARKED) {
			T.eq(11, m.misses.get(k), k + ": 11 misses");
		}
		T.check(m.marked.isEmpty(), "nothing marked before 12");
		m.cycle(devel(), delivered(), complete().kinds());
		T.eq(LEGACY_MARKED, m.marked,
				"every legacy object but the World is marked at the 12th cycle");
		T.check(!m.misses.containsKey("world:legacy")
				&& !m.marked.contains("world:legacy"), "the legacy World is never counted");
		for (String k : delivered()) {
			T.check(!m.misses.containsKey(k), k + ": delivered, no miss");
		}
	}

	/** Every kind but {@code missing} is read; {@code missing} is not. */
	private static void assertOnlyOut(Set<String> kinds, Set<String> missing,
			String why) {
		Set<String> want = complete().kinds();
		want = new LinkedHashSet<>(want);
		want.removeAll(missing);
		T.eq(want, kinds, why);
	}

	/**
	 * {@code kinds} declared for 30 cycles in which no object of
	 * {@code kind} is delivered: nothing of it is counted or marked.
	 */
	private static void marksNothingOf(Set<String> kinds, String kind,
			String why) {
		Map<String, String> known = new HashMap<>();
		known.put(kind + ":a", kind);
		known.put(kind + ":b", kind);
		Model m = new Model();
		for (int i = 0; i < 30; i++) m.cycle(known, set(), kinds);
		T.check(m.misses.isEmpty() && m.marked.isEmpty(), why);
	}

	static void upsReadFailureMarksNoUps() {
		ReadKinds r = complete();
		r.ups(false, false, false, false);
		assertOnlyOut(r.kinds(), set(ReadKinds.UPS), "UPS read failed: no UPS kind");
		marksNothingOf(r.kinds(), ReadKinds.UPS, "30 failed UPS reads: no UPS counted");
		// The failure alone decides, whatever the other inputs say.
		r = complete();
		r.ups(false, true, true, true);
		assertOnlyOut(r.kinds(), set(ReadKinds.UPS), "UPS read failed (other inputs set)");
	}

	static void upsAnswerWithoutConnectFieldMarksNoUps() {
		ReadKinds r = complete();
		r.ups(true, false, false, false);
		assertOnlyOut(r.kinds(), set(ReadKinds.UPS),
				"no usb_ups_connect: not known gone");
	}

	static void connectedUpsWithoutModelMarksNoUps() {
		ReadKinds r = complete();
		r.ups(true, true, true, false);
		assertOnlyOut(r.kinds(), set(ReadKinds.UPS),
				"connected UPS with no model: keyed \"UPS\", not read");
	}

	/** DSM answered, no UPS connected: the old UPS is gone. */
	static void disconnectedUpsIsCountedGone() {
		ReadKinds r = complete();
		r.ups(true, true, false, false);
		T.check(r.kinds().contains(ReadKinds.UPS), "no UPS connected is a read");
	}

	static void ssdCacheReadFailureMarksNoCache() {
		ReadKinds r = complete();
		r.ssdCaches(false, 0);
		assertOnlyOut(r.kinds(), set(ReadKinds.CACHE), "no ssdCaches list");
		marksNothingOf(r.kinds(), ReadKinds.CACHE, "no SSD cache counted");
		r = complete();
		r.ssdCaches(true, 1);
		assertOnlyOut(r.kinds(), set(ReadKinds.CACHE), "a cache without id");
	}

	static void iscsiReadFailureMarksNoLun() {
		ReadKinds r = complete();
		r.luns(false, false, 0);
		assertOnlyOut(r.kinds(), set(ReadKinds.LUN), "LUN list unread");
		marksNothingOf(r.kinds(), ReadKinds.LUN, "no LUN counted");
		r = complete();
		r.luns(false, true, 0); // unread decides, whatever the payload
		assertOnlyOut(r.kinds(), set(ReadKinds.LUN), "LUN list unread (list set)");
		r = complete();
		r.luns(true, false, 0); // DSM 102, not installed: no list
		assertOnlyOut(r.kinds(), set(ReadKinds.LUN), "LUN endpoint with no list");
		r = complete();
		r.luns(true, true, 1);
		assertOnlyOut(r.kinds(), set(ReadKinds.LUN), "a LUN without uuid");
	}

	static void systemHealthReadFailureMarksNoDiskStation() {
		ReadKinds r = complete();
		r.systemHealth(false);
		assertOnlyOut(r.kinds(), set(ReadKinds.DISKSTATION),
				"SystemHealth unread: no DiskStation kind");
		marksNothingOf(r.kinds(), ReadKinds.DISKSTATION, "no DiskStation counted");
	}

	/**
	 * One share's NFS rule probe failed: its export is not delivered while
	 * the other exports are. No export kind is declared; nothing is counted.
	 */
	static void nfsProbeFailureMarksNoExport() {
		ReadKinds r = complete();
		r.exports(true, true, 0, false, 1);
		Set<String> k = r.kinds();
		assertOnlyOut(k, set(ReadKinds.EXPORT), "one failed probe: no export kind");
		Map<String, String> known = new HashMap<>();
		known.put("export:ok", ReadKinds.EXPORT);
		known.put("export:probe-failed", ReadKinds.EXPORT);
		known.put("export:legacy", ReadKinds.EXPORT);
		Model m = new Model();
		for (int i = 0; i < 30; i++) m.cycle(known, set("export:ok"), k);
		T.check(m.misses.isEmpty() && m.marked.isEmpty(),
				"30 cycles with a failed probe: no export counted or marked");
	}

	static void shareListUnreadMarksNoExport() {
		ReadKinds r = complete();
		r.exports(false, false, 0, false, 0);
		assertOnlyOut(r.kinds(), set(ReadKinds.EXPORT), "share list unread");
		r = complete();
		r.exports(false, true, 0, false, 0);
		assertOnlyOut(r.kinds(), set(ReadKinds.EXPORT), "share list unread (list set)");
		r = complete();
		r.exports(true, true, 1, false, 0);
		assertOnlyOut(r.kinds(), set(ReadKinds.EXPORT), "a share without name");
	}

	static void nfsServiceOffMarksNoExport() {
		ReadKinds r = complete();
		r.exports(true, true, 0, true, 0);
		assertOnlyOut(r.kinds(), set(ReadKinds.EXPORT),
				"NFS off: exports are ERROR, not gone");
	}

	static void incompleteLoadInfoMarksNoPoolVolumeOrDisk() {
		ReadKinds r = complete();
		r.storage(true, true, true, 7, 1);
		assertOnlyOut(r.kinds(), STORAGE, "a record without identity");
		r = complete();
		r.storage(true, false, true, 7, 0);
		assertOnlyOut(r.kinds(), STORAGE, "no volume list");
		r = complete();
		r.storage(true, true, true, 0, 0);
		assertOnlyOut(r.kinds(), STORAGE, "zero disks is not a complete read");
		marksNothingOf(r.kinds(), ReadKinds.DISK, "no disk counted");
		T.check(r.kinds().contains(ReadKinds.CACHE),
				"SSD caches are their own read");
	}

	static void worldIsExcluded() {
		T.check(ReadKinds.EXCLUDED.contains(ReadKinds.WORLD), "World excluded");
		T.check(!complete().kinds().contains(ReadKinds.WORLD), "World never named");
		T.check(ReadKinds.noId(null) && ReadKinds.noId("") && !ReadKinds.noId("x"),
				"noId");
	}
}
