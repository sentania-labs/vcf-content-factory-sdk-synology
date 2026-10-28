package com.vcfcf.adapters.synology;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Build 35 (review R4-W1, R4-W2): DSM Storage Manager's storage verdict
 * (StorageOverview). The healthy values are the lab DS1520+'s load_info
 * overview_data (DSM 7.3.2, read-only probe 2026-10-28); the degraded ones
 * are the other values of the same response's sample_level_str, compared the
 * way storage_panel.js's health card compares them, since the lab has no
 * failed pool or expansion unit. Run from the repo root
 * (ci/run_java_tests.sh).
 */
public final class StorageOverviewTest {

	public static void main(String[] args) {
		levelFollowsSampleLevelStr();
		levelUsesUiDefaultsWithoutSampleLevelStr();
		unknownOrAbsentLevelIsSkipped();
		flagsAndCounts();
		expansionUnitEvidence();
		diskNamingUnlistedPool();
		System.out.println("StorageOverviewTest: all assertions passed");
	}

	/** The lab's sample_level_str, verbatim. */
	static Map<String, String> lab() {
		Map<String, String> m = new HashMap<>();
		m.put("attention", "status_level_abnormal");
		m.put("danger", "status_level_danger");
		m.put("normal", "status_level_normal");
		return m;
	}

	static void levelFollowsSampleLevelStr() {
		T.eq("normal", StorageOverview.level("status_level_normal", lab()), "lab: normal");
		T.eq("attention", StorageOverview.level("status_level_abnormal", lab()), "abnormal is attention");
		T.eq("danger", StorageOverview.level("status_level_danger", lab()), "danger");
	}

	static void levelUsesUiDefaultsWithoutSampleLevelStr() {
		T.eq("danger", StorageOverview.level("danger", null), "UI default danger");
		T.eq("attention", StorageOverview.level("abnormal", null), "UI default abnormal");
		T.eq("normal", StorageOverview.level("normal", null), "UI default normal");
		T.eq(null, StorageOverview.level("status_level_normal", null),
				"7.3.2 string without its table: no guess");
	}

	static void unknownOrAbsentLevelIsSkipped() {
		T.eq(null, StorageOverview.level(null, lab()), "absent");
		T.eq(null, StorageOverview.level("  ", lab()), "blank");
		T.eq(null, StorageOverview.level("status_level_other", lab()), "unknown value");
		T.eq(null, StorageOverview.level("status_level_normal",
				Collections.<String, String>emptyMap()), "sample_level_str not an object");
	}

	static void flagsAndCounts() {
		T.eq("false", StorageOverview.flag("false"), "false");
		T.eq("true", StorageOverview.flag("true"), "true");
		T.eq(null, StorageOverview.flag(null), "absent skipped");
		T.eq(null, StorageOverview.flag("1"), "not a boolean skipped");
		T.eq("0", StorageOverview.count(true, 0), "empty list is 0");
		T.eq("2", StorageOverview.count(true, 2), "two");
		T.eq(null, StorageOverview.count(false, 0), "no list skipped, never 0");
	}

	/** Review R4-W1: absent is no evidence; true, non-empty or unreadable is affected. */
	static void expansionUnitEvidence() {
		T.check(!StorageOverview.eunitLost(false, null), "lost absent");
		T.check(!StorageOverview.eunitLost(true, "false"), "lost false (lab)");
		T.check(StorageOverview.eunitLost(true, "true"), "lost true");
		T.check(StorageOverview.eunitLost(true, "yes"), "lost unreadable");
		T.check(!StorageOverview.eunitIncompatible(false, false, 0), "incompatible absent");
		T.check(!StorageOverview.eunitIncompatible(true, true, 0), "[] (lab)");
		T.check(StorageOverview.eunitIncompatible(true, true, 1), "one incompatible unit");
		T.check(StorageOverview.eunitIncompatible(true, false, 0), "not a list");
	}

	/** Review R4-W1: a disk names a pool storagePools omits. */
	static void diskNamingUnlistedPool() {
		List<String> pools = Arrays.asList("reuse_1");
		List<String> shared = Arrays.asList("shared_cache_1");
		List<String> lab = Arrays.asList("reuse_1", "reuse_1", "reuse_1",
				"reuse_1", "reuse_1", "shared_cache_1", "shared_cache_1");
		T.check(!StorageOverview.diskNamesUnlistedPool(lab, pools, shared),
				"lab: every used_by is listed");
		T.check(StorageOverview.diskNamesUnlistedPool(
				Arrays.asList("reuse_1", "reuse_2"), pools, shared),
				"reuse_2 left storagePools: dangling");
		T.check(!StorageOverview.diskNamesUnlistedPool(
				Arrays.asList("reuse_1", "", null), pools, shared),
				"unused and unknown used_by name nothing");
		T.check(StorageOverview.diskNamesUnlistedPool(
				Arrays.asList("shared_cache_1"), pools, null),
				"sharedCaches not a list: cache disk is unknown, fails safe");
		T.check(!StorageOverview.diskNamesUnlistedPool(null, pools, shared), "no disks");
	}
}
