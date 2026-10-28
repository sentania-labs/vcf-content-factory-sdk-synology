package com.vcfcf.adapters.synology;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
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
		completeReadNamesEveryKind();
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
		emptyShareListMarksNoExport();
		zeroExportsMarksNoExport();
		emptyLunListMarksNoLun();
		emptyPoolListNeedsDiskEvidence();
		emptyVolumeListWithAPoolMarksNoVolume();
		noPoolConfiguredEvidence();
		emptyCacheListNeedsEvidence();
		dsmErrorIn200IsUnread();
		missingOrDetectedPoolKeepsStorage();
		absentUnassembledKeysAreNoEvidence();
		noPoolConfiguredNeedsNoUnassembledPool();
		volumeNamingUnlistedPoolKeepsPools();
		unlistedOrMissingCacheKeepsCaches();
		pagedShareListMarksNoExport();
		diskNamingUnlistedPoolKeepsStorage();
		expansionUnitLostOrIncompatibleKeepsStorage();
		worldCountedWithTheDiskStation();
		System.out.println("ReadKindsTest: all assertions passed");
	}

	/** A healthy devel-shaped cycle: every read succeeded, a UPS connected. */
	private static ReadKinds complete() {
		ReadKinds r = new ReadKinds();
		r.systemHealth(true);
		r.unassembledPools(true, 0, true, 0);
		r.contradictions(false, false, false);
		r.disksNameUnlistedPool(false);
		r.expansionUnits(false, false);
		r.storage(true, true, true, 1, 1, 7, 0, false);
		r.ssdCaches(true, 1, 0, false);
		r.luns(true, true, 2, 0);
		r.exports(true, true, 12, -1, 0, false, 0, 10);
		r.ups(true, true, true, true);
		return r;
	}

	private static Set<String> set(String... kinds) {
		return new LinkedHashSet<>(Arrays.asList(kinds));
	}

	private static final Set<String> STORAGE = set(ReadKinds.POOL,
			ReadKinds.VOLUME, ReadKinds.DISK);

	static void completeReadNamesEveryKind() {
		T.eq(set(ReadKinds.WORLD, ReadKinds.DISKSTATION, ReadKinds.POOL,
				ReadKinds.VOLUME, ReadKinds.DISK, ReadKinds.CACHE,
				ReadKinds.LUN, ReadKinds.EXPORT, ReadKinds.UPS),
				complete().kinds(), "a complete read names every kind");
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
			"world:legacy", ReadKinds.POOL + ":legacy", ReadKinds.VOLUME + ":legacy",
			ReadKinds.DISK + ":legacy", ReadKinds.CACHE + ":legacy",
			ReadKinds.LUN + ":legacy", ReadKinds.EXPORT + ":legacy");

	/**
	 * A complete read: every pre-build-34 orphan, the legacy World among
	 * them, takes a miss and is marked at the 12th cycle; the current World
	 * is delivered every such cycle and never takes one.
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
				"every legacy object, the old World too, is marked at the 12th cycle");
		T.check(m.marked.contains("world:legacy"), "the legacy World is marked");
		// Long after: the current World is still never counted or marked.
		for (int i = 0; i < 30; i++) {
			m.cycle(devel(), delivered(), complete().kinds());
		}
		T.check(!m.misses.containsKey("world:serial")
				&& !m.marked.contains("world:serial"),
				"the current World is never counted or marked");
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
		r.ssdCaches(false, 0, 0, false);
		assertOnlyOut(r.kinds(), set(ReadKinds.CACHE), "no ssdCaches list");
		marksNothingOf(r.kinds(), ReadKinds.CACHE, "no SSD cache counted");
		r = complete();
		r.ssdCaches(true, 1, 1, false);
		assertOnlyOut(r.kinds(), set(ReadKinds.CACHE), "a cache without id");
	}

	static void iscsiReadFailureMarksNoLun() {
		ReadKinds r = complete();
		r.luns(false, false, 2, 0);
		assertOnlyOut(r.kinds(), set(ReadKinds.LUN), "LUN list unread");
		marksNothingOf(r.kinds(), ReadKinds.LUN, "no LUN counted");
		r = complete();
		r.luns(false, true, 2, 0); // unread decides, whatever the payload
		assertOnlyOut(r.kinds(), set(ReadKinds.LUN), "LUN list unread (list set)");
		r = complete();
		r.luns(true, false, 2, 0); // DSM 102, not installed: no list
		assertOnlyOut(r.kinds(), set(ReadKinds.LUN), "LUN endpoint with no list");
		r = complete();
		r.luns(true, true, 2, 1);
		assertOnlyOut(r.kinds(), set(ReadKinds.LUN), "a LUN without uuid");
	}

	static void systemHealthReadFailureMarksNoDiskStation() {
		ReadKinds r = complete();
		r.systemHealth(false);
		assertOnlyOut(r.kinds(), set(ReadKinds.WORLD, ReadKinds.DISKSTATION),
				"SystemHealth unread: no DiskStation or World kind");
		marksNothingOf(r.kinds(), ReadKinds.DISKSTATION, "no DiskStation counted");
		marksNothingOf(r.kinds(), ReadKinds.WORLD, "no World counted");
	}

	/**
	 * One share's NFS rule probe failed: its export is not delivered while
	 * the other exports are. No export kind is declared; nothing is counted.
	 */
	static void nfsProbeFailureMarksNoExport() {
		ReadKinds r = complete();
		r.exports(true, true, 12, -1, 0, false, 1, 10);
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
		r.exports(false, false, 12, -1, 0, false, 0, 10);
		assertOnlyOut(r.kinds(), set(ReadKinds.EXPORT), "share list unread");
		r = complete();
		r.exports(false, true, 12, -1, 0, false, 0, 10);
		assertOnlyOut(r.kinds(), set(ReadKinds.EXPORT), "share list unread (list set)");
		r = complete();
		r.exports(true, true, 12, -1, 1, false, 0, 10);
		assertOnlyOut(r.kinds(), set(ReadKinds.EXPORT), "a share without name");
	}

	static void nfsServiceOffMarksNoExport() {
		ReadKinds r = complete();
		r.exports(true, true, 12, -1, 0, true, 0, 10);
		assertOnlyOut(r.kinds(), set(ReadKinds.EXPORT),
				"NFS off: exports are ERROR, not gone");
	}

	static void incompleteLoadInfoMarksNoPoolVolumeOrDisk() {
		ReadKinds r = complete();
		r.storage(true, true, true, 1, 1, 7, 1, false);
		assertOnlyOut(r.kinds(), STORAGE, "a record without identity");
		r = complete();
		r.storage(true, false, true, 1, 1, 7, 0, false);
		assertOnlyOut(r.kinds(), STORAGE, "no volume list");
		r = complete();
		r.storage(true, true, true, 1, 1, 0, 0, false);
		assertOnlyOut(r.kinds(), STORAGE, "zero disks is not a complete read");
		marksNothingOf(r.kinds(), ReadKinds.DISK, "no disk counted");
		T.check(r.kinds().contains(ReadKinds.CACHE),
				"SSD caches are their own read");
	}

	/**
	 * Build 35 (devel proof): the World is not excluded, and is named
	 * exactly when the DiskStation is, whatever else failed: it is
	 * delivered from the DiskStation's read.
	 */
	static void worldCountedWithTheDiskStation() {
		T.check(ReadKinds.EXCLUDED.isEmpty(), "nothing excluded");
		T.check(complete().kinds().contains(ReadKinds.WORLD), "World named");
		ReadKinds r = new ReadKinds();
		r.systemHealth(true);
		T.eq(set(ReadKinds.WORLD, ReadKinds.DISKSTATION), r.kinds(),
				"every other read failed: World and DiskStation still named");
		r = new ReadKinds();
		T.check(r.kinds().isEmpty(), "nothing read: World not named");
		T.check(ReadKinds.noId(null) && ReadKinds.noId("") && !ReadKinds.noId("x"),
				"noId");
	}

	// --- Build 35 review: an empty answer is not proof everything is gone ---

	/** devel's disks: five in pool reuse_1, two in shared cache 1. */
	private static final List<String> DEVEL_DISKS = Arrays.asList("reuse_1",
			"reuse_1", "reuse_1", "reuse_1", "reuse_1", "shared_cache_1",
			"shared_cache_1");
	private static final List<String> POOLS = Collections.singletonList("reuse_1");
	private static final List<String> SHARED = Collections.singletonList("shared_cache_1");
	private static final List<String> NONE = Collections.emptyList();

	static void emptyShareListMarksNoExport() {
		ReadKinds r = complete();
		r.exports(true, true, 0, -1, 0, false, 0, 0);
		assertOnlyOut(r.kinds(), set(ReadKinds.EXPORT), "shares: [] is not proof");
		marksNothingOf(r.kinds(), ReadKinds.EXPORT,
				"30 cycles of an empty share list: no export counted");
	}

	/** Every probe answered "no rules": not proof either. */
	static void zeroExportsMarksNoExport() {
		ReadKinds r = complete();
		r.exports(true, true, 12, -1, 0, false, 0, 0);
		assertOnlyOut(r.kinds(), set(ReadKinds.EXPORT), "zero exports is not proof");
		r = complete();
		r.exports(true, true, 12, -1, 0, false, 0, 1);
		T.check(r.kinds().contains(ReadKinds.EXPORT), "one export: read");
	}

	static void emptyLunListMarksNoLun() {
		ReadKinds r = complete();
		r.luns(true, true, 0, 0);
		assertOnlyOut(r.kinds(), set(ReadKinds.LUN), "luns: [] is not proof");
		marksNothingOf(r.kinds(), ReadKinds.LUN,
				"30 cycles of an empty LUN list: no LUN counted");
	}

	static void emptyPoolListNeedsDiskEvidence() {
		// No evidence: pools and volumes out, disks (non-empty) still read.
		ReadKinds r = complete();
		r.storage(true, true, true, 0, 0, 7, 0, false);
		assertOnlyOut(r.kinds(), set(ReadKinds.POOL, ReadKinds.VOLUME),
				"storagePools: [] without evidence");
		marksNothingOf(r.kinds(), ReadKinds.POOL, "no pool counted");
		// Evidence, but a volume is listed: a volume needs a pool.
		r = complete();
		r.storage(true, true, true, 0, 1, 7, 0, true);
		assertOnlyOut(r.kinds(), set(ReadKinds.POOL),
				"no pool listed but a volume is: pools not read");
		// Evidence and no volume: both really empty.
		r = complete();
		r.storage(true, true, true, 0, 0, 7, 0, true);
		T.eq(complete().kinds(), r.kinds(),
				"no pool, no volume, every disk unused: read");
		// The evidence does not rescue an incomplete load_info.
		r = complete();
		r.storage(true, true, true, 0, 0, 0, 0, true);
		assertOnlyOut(r.kinds(), STORAGE, "zero disks: nothing read");
	}

	static void emptyVolumeListWithAPoolMarksNoVolume() {
		ReadKinds r = complete();
		r.storage(true, true, true, 1, 0, 7, 0, false);
		assertOnlyOut(r.kinds(), set(ReadKinds.VOLUME),
				"volumes: [] on a pool: not proof");
		r = complete();
		r.storage(true, true, true, 1, 0, 7, 0, true);
		assertOnlyOut(r.kinds(), set(ReadKinds.VOLUME),
				"evidence of no pool contradicts a listed pool: volumes not read");
	}

	static void noPoolConfiguredEvidence() {
		T.check(!ReadKinds.noPoolConfigured(DEVEL_DISKS, SHARED, true),
				"devel: disks name reuse_1, a pool");
		T.check(ReadKinds.noPoolConfigured(Arrays.asList("", "", "shared_cache_1"),
				SHARED, true), "unused disks and a cache disk: no pool");
		T.check(!ReadKinds.noPoolConfigured(Arrays.asList("", null), SHARED, true),
				"a disk with no used_by is no evidence");
		T.check(!ReadKinds.noPoolConfigured(Arrays.asList("", "shared_cache_1"),
				null, true), "sharedCaches not a list: a cache disk is unexplained");
		T.check(!ReadKinds.noPoolConfigured(NONE, SHARED, true), "no disks: no evidence");
		T.check(!ReadKinds.noPoolConfigured(null, SHARED, true), "no disk list: no evidence");
	}

	static void emptyCacheListNeedsEvidence() {
		// devel's load_info with ssdCaches: [] is contradicted three ways.
		T.check(!ReadKinds.noCacheConfigured(SHARED, DEVEL_DISKS, POOLS, true),
				"devel: a shared cache, cache disks, a cached volume");
		ReadKinds r = complete();
		r.ssdCaches(true, 0, 0, false);
		assertOnlyOut(r.kinds(), set(ReadKinds.CACHE), "ssdCaches: [] without evidence");
		marksNothingOf(r.kinds(), ReadKinds.CACHE, "no SSD cache counted");

		List<String> poolOnly = Arrays.asList("reuse_1", "reuse_1", "");
		T.check(ReadKinds.noCacheConfigured(NONE, poolOnly, POOLS, false),
				"no cache group, no cached volume, disks in a pool or unused");
		T.check(!ReadKinds.noCacheConfigured(null, poolOnly, POOLS, false),
				"sharedCaches not a list: no evidence");
		T.check(!ReadKinds.noCacheConfigured(SHARED, poolOnly, POOLS, false),
				"a cache group exists");
		T.check(!ReadKinds.noCacheConfigured(NONE, poolOnly, POOLS, true),
				"a volume names a cache");
		T.check(!ReadKinds.noCacheConfigured(NONE,
				Arrays.asList("reuse_1", "shared_cache_1"), POOLS, false),
				"a disk names a cache group");
		T.check(!ReadKinds.noCacheConfigured(NONE,
				Arrays.asList("reuse_1", null), POOLS, false),
				"a disk with no used_by");
		T.check(!ReadKinds.noCacheConfigured(NONE, NONE, POOLS, false),
				"no disks: no evidence");

		r = complete();
		r.ssdCaches(true, 0, 0, true);
		T.check(r.kinds().contains(ReadKinds.CACHE), "ssdCaches: [] with evidence: read");
	}

	/**
	 * A DSM error in a 200 ({@code success: false}) throws in
	 * {@code SynologyApiClient.call} (wiring pinned in
	 * tests/test_vanished_wiring.py), so the endpoint is unread
	 * (SnapshotReadsTest), and an unread endpoint declares nothing, whatever
	 * the stored value holds.
	 */
	static void dsmErrorIn200IsUnread() {
		ReadKinds r = complete();
		r.luns(false, true, 2, 0);
		r.exports(false, true, 12, -1, 0, false, 0, 10);
		assertOnlyOut(r.kinds(), set(ReadKinds.LUN, ReadKinds.EXPORT),
				"LUN and share lists unread on a DSM error");
		r = complete();
		r.ups(false, true, false, false);
		assertOnlyOut(r.kinds(), set(ReadKinds.UPS), "UPS unread on a DSM error");
	}

	// --- Build 35 review round 3 ---

	private static final Set<String> ON_A_POOL = set(ReadKinds.POOL,
			ReadKinds.VOLUME, ReadKinds.DISK, ReadKinds.CACHE, ReadKinds.LUN,
			ReadKinds.EXPORT);

	/**
	 * Review R3-B1: an expansion unit's pool in missing_pools, or a pool
	 * awaiting Online Assemble in detected_pools, has left storagePools
	 * (and its volumes and disks may have left theirs). Nothing that can
	 * live on it is counted, for 30 cycles; DiskStation and UPS still are.
	 */
	static void missingOrDetectedPoolKeepsStorage() {
		for (int[] md : new int[][] {{1, 0}, {0, 1}, {2, 3}}) {
			ReadKinds r = complete();
			r.unassembledPools(true, md[0], true, md[1]);
			String why = "missing=" + md[0] + " detected=" + md[1];
			assertOnlyOut(r.kinds(), ON_A_POOL, why + ": no storage kind");
			for (String kind : ON_A_POOL) {
				marksNothingOf(r.kinds(), kind, why + ": no " + kind + " counted");
			}
			T.check(r.kinds().contains(ReadKinds.DISKSTATION)
					&& r.kinds().contains(ReadKinds.UPS), why + ": DS and UPS read");
		}
		// The multi-pool path: the internal pool is still listed, so pools
		// > 0; before the fix the DX517 pool counted a miss every cycle.
		ReadKinds r = complete();
		r.storage(true, true, true, 1, 1, 5, 0, false);
		r.unassembledPools(true, 1, true, 0);
		Map<String, String> known = new HashMap<>();
		known.put("pool:internal", ReadKinds.POOL);
		known.put("pool:dx517", ReadKinds.POOL);
		known.put("vol:dx517", ReadKinds.VOLUME);
		known.put("disk:dx517-1", ReadKinds.DISK);
		Model m = new Model();
		for (int i = 0; i < 30; i++) {
			m.cycle(known, set("pool:internal"), r.kinds());
		}
		T.check(m.misses.isEmpty() && m.marked.isEmpty(),
				"30 cycles with the DX517 pool missing: nothing counted");
	}

	/** Review R3-B1: a key DSM did not send is no evidence either way. */
	static void absentUnassembledKeysAreNoEvidence() {
		ReadKinds a = complete();
		a.unassembledPools(false, 0, true, 0);
		assertOnlyOut(a.kinds(), ON_A_POOL, "no missing_pools key");
		ReadKinds b = complete();
		b.unassembledPools(true, 0, false, 0);
		assertOnlyOut(b.kinds(), ON_A_POOL, "no detected_pools key");
		ReadKinds c = new ReadKinds();
		c.systemHealth(true);
		c.storage(true, true, true, 1, 1, 7, 0, false);
		T.eq(set(ReadKinds.WORLD, ReadKinds.DISKSTATION), c.kinds(),
				"unassembledPools never called: no storage kind");
		T.check(ReadKinds.noUnassembledPool(true, 0, true, 0), "both empty lists");
		T.check(!ReadKinds.noUnassembledPool(true, 0, false, 0), "detected absent");
		T.check(!ReadKinds.noUnassembledPool(false, 0, true, 0), "missing absent");
		T.check(!ReadKinds.noUnassembledPool(true, 1, true, 0), "one missing");
		T.check(!ReadKinds.noUnassembledPool(true, 0, true, 1), "one detected");
	}

	/**
	 * Review R3-B1, single-pool path: all HDDs pulled, the NVMe cache left,
	 * so the disks that remain name only the shared cache. Without the
	 * unassembled-pool evidence that is not "no pool".
	 */
	static void noPoolConfiguredNeedsNoUnassembledPool() {
		List<String> cacheOnly = Arrays.asList("shared_cache_1", "shared_cache_1");
		T.check(ReadKinds.noPoolConfigured(cacheOnly, SHARED, true),
				"cache disks only, no missing pool: no pool");
		T.check(!ReadKinds.noPoolConfigured(cacheOnly, SHARED, false),
				"cache disks only, a missing pool: not evidence");
		ReadKinds r = complete();
		r.unassembledPools(true, 1, true, 0);
		r.storage(true, true, true, 0, 0, 2, 0,
				ReadKinds.noPoolConfigured(cacheOnly, SHARED, false));
		assertOnlyOut(r.kinds(), ON_A_POOL, "HDDs pulled: nothing on a pool read");
	}

	/** Review R3-W1: a volume's pool_path names a pool storagePools omits. */
	static void volumeNamingUnlistedPoolKeepsPools() {
		ReadKinds r = complete();
		r.contradictions(true, false, false);
		assertOnlyOut(r.kinds(), set(ReadKinds.POOL), "dangling pool_path");
		marksNothingOf(r.kinds(), ReadKinds.POOL, "no pool counted");
	}

	/**
	 * Review R3-W1: a volume's cache.id names a cache ssdCaches omits, or a
	 * shared cache is_missing.
	 */
	static void unlistedOrMissingCacheKeepsCaches() {
		ReadKinds a = complete();
		a.contradictions(false, true, false);
		assertOnlyOut(a.kinds(), set(ReadKinds.CACHE), "dangling cache.id");
		ReadKinds b = complete();
		b.contradictions(false, false, true);
		assertOnlyOut(b.kinds(), set(ReadKinds.CACHE), "shared cache is_missing");
		marksNothingOf(b.kinds(), ReadKinds.CACHE, "no cache counted");
	}

	/**
	 * Review R4-W1: a multi-pool NAS whose pool B left storagePools without
	 * entering missing_pools or detected_pools; its disks still name it. Pool
	 * A is still listed (pools > 0), so before the fix Pool was declared and
	 * B counted a miss every cycle. PR #47 Codex P1: only Pool was left out,
	 * so the volumes, disks, caches, LUNs and exports that went missing with
	 * B still counted misses and were marked at the 12th cycle. The disk
	 * evidence is in the shared gate now: every kind that can live on a pool
	 * is left out, as with a missing or detected pool; DiskStation and UPS
	 * still count.
	 */
	static void diskNamingUnlistedPoolKeepsStorage() {
		ReadKinds r = complete();
		r.disksNameUnlistedPool(StorageOverview.diskNamesUnlistedPool(
				Arrays.asList("reuse_1", "reuse_2", "reuse_2"),
				Arrays.asList("reuse_1"), Arrays.asList("shared_cache_1"),
				Arrays.asList("alloc_cache_1_1")));
		assertOnlyOut(r.kinds(), ON_A_POOL, "disk names reuse_2");
		for (String kind : ON_A_POOL) {
			marksNothingOf(r.kinds(), kind, "disk names reuse_2: no " + kind
					+ " counted");
		}
		T.check(r.kinds().contains(ReadKinds.DISKSTATION)
				&& r.kinds().contains(ReadKinds.UPS),
				"disk names reuse_2: DS and UPS read");
		// Pool B and what lived on it are gone from every list; pool A and
		// its objects are still delivered. Nothing of B is counted.
		Map<String, String> known = new HashMap<>();
		known.put("pool:reuse_1", ReadKinds.POOL);
		known.put("vol:1", ReadKinds.VOLUME);
		known.put("disk:1", ReadKinds.DISK);
		known.put("pool:reuse_2", ReadKinds.POOL);
		known.put("vol:2", ReadKinds.VOLUME);
		known.put("disk:2", ReadKinds.DISK);
		known.put("cache:2", ReadKinds.CACHE);
		known.put("lun:2", ReadKinds.LUN);
		known.put("export:2", ReadKinds.EXPORT);
		Model m = new Model();
		for (int i = 0; i < 30; i++) {
			m.cycle(known, set("pool:reuse_1", "vol:1", "disk:1"), r.kinds());
		}
		T.check(m.misses.isEmpty() && m.marked.isEmpty(),
				"30 cycles with reuse_2 unlisted: nothing on any pool counted");
		// The disks name only listed pools again: the gate opens.
		ReadKinds ok = complete();
		ok.disksNameUnlistedPool(StorageOverview.diskNamesUnlistedPool(
				Arrays.asList("reuse_1", "reuse_1"), Arrays.asList("reuse_1"),
				Arrays.asList("shared_cache_1"),
				Arrays.asList("alloc_cache_1_1")));
		T.eq(complete().kinds(), ok.kinds(), "every disk names a listed pool");
	}

	/**
	 * Review R4-W1: DSM's overview_data says an expansion unit was lost or
	 * is incompatible. Nothing that can live on its pool is counted, for 30
	 * cycles, as with an unassembled pool; DiskStation and UPS still are.
	 */
	static void expansionUnitLostOrIncompatibleKeepsStorage() {
		for (boolean[] li : new boolean[][] {{true, false}, {false, true}, {true, true}}) {
			ReadKinds r = complete();
			r.expansionUnits(li[0], li[1]);
			String why = "lost=" + li[0] + " incompatible=" + li[1];
			assertOnlyOut(r.kinds(), ON_A_POOL, why + ": no storage kind");
			for (String kind : ON_A_POOL) {
				marksNothingOf(r.kinds(), kind, why + ": no " + kind + " counted");
			}
		}
		ReadKinds ok = complete();
		ok.expansionUnits(StorageOverview.eunitLost(false, null),
				StorageOverview.eunitIncompatible(false, false, 0));
		T.eq(complete().kinds(), ok.kinds(), "fields absent: no effect");
	}

	/** Review R3-N1: a share list page short of its total is partial. */
	static void pagedShareListMarksNoExport() {
		ReadKinds r = complete();
		r.exports(true, true, 12, 20, 0, false, 0, 10);
		assertOnlyOut(r.kinds(), set(ReadKinds.EXPORT), "12 of total 20: partial");
		marksNothingOf(r.kinds(), ReadKinds.EXPORT, "no export counted");
		r = complete();
		r.exports(true, true, 12, 12, 0, false, 0, 10);
		T.check(r.kinds().contains(ReadKinds.EXPORT), "total equals rows: read");
		r = complete();
		r.exports(true, true, 12, -1, 0, false, 0, 10);
		T.check(r.kinds().contains(ReadKinds.EXPORT), "no total: read");
	}
}
