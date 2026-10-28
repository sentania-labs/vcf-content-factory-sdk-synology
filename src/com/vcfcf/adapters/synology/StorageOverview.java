package com.vcfcf.adapters.synology;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Build 35 (review R4-W1, R4-W2): DSM Storage Manager's own storage health
 * verdict, from {@code SYNO.Storage.CGI.Storage load_info}
 * {@code overview_data} and {@code env.status}, as DiskStation properties,
 * and the load_info pointers that say a pool or expansion unit is gone
 * without being in any list. No VCF Ops SDK and no framework-jar
 * dependency, so the plain-main tests run exactly this code.
 *
 * <p><b>Source.</b> DSM's Storage Manager (DSM 7.3.2,
 * {@code webman/3rdparty/StorageManager/storage_panel.js}, read 2026-10-28)
 * draws the Overview page's health card ({@code health-card},
 * {@code overviewHealthStatus}) from these fields. {@code loadOverview}
 * takes {@code status_level}, {@code sample_level_str} (else its defaults
 * {@code danger:"danger", attention:"abnormal", normal:"normal"}),
 * {@code space_affect_by_eunit_lost} and {@code incompatible_eunit}; the
 * card compares {@code status_level} with {@code sample_level_str}'s values
 * ({@code isLevelDanger}, {@code isLevelAttention}). Danger shows
 * "Critical" with "Issues have occurred to Storage Pool {0}", or, when
 * {@code space_affect_by_eunit_lost}, "Storage Pool {0} has encountered
 * critical issues, possibly due to connection issues between {1} and the
 * expansion units"; a non-empty {@code incompatible_eunit} shows "Warning",
 * "An incompatible expansion unit ({0}) has been detected"; {@code
 * env.status.system_crashed} is Critical and {@code system_need_repair}
 * Warning ("repair system partition"). The lab DS1520+ sends
 * {@code status_level: "status_level_normal"}, {@code sample_level_str}
 * {@code {normal: "status_level_normal", attention: "status_level_abnormal",
 * danger: "status_level_danger"}}, {@code space_affect_by_eunit_lost: false},
 * {@code incompatible_eunit: []}, {@code system_crashed: false},
 * {@code system_need_repair: false}. Not yet seen degraded: the lab has no
 * failed pool or expansion unit.
 */
final class StorageOverview {

	private StorageOverview() {}

	static final String DANGER = "danger";
	static final String ATTENTION = "attention";
	static final String NORMAL = "normal";

	/** storage_panel.js OverviewModule state, used when DSM sends no sample_level_str. */
	static final Map<String, String> UI_DEFAULT_LEVELS;
	static {
		Map<String, String> m = new LinkedHashMap<>();
		m.put(DANGER, "danger");
		m.put(ATTENTION, "abnormal");
		m.put(NORMAL, "normal");
		UI_DEFAULT_LEVELS = Collections.unmodifiableMap(m);
	}

	/**
	 * DSM's storage level, {@code danger}, {@code attention} or
	 * {@code normal}: the name whose {@code sample_level_str} value equals
	 * {@code status_level}, as the card decides it. {@code null}, so the
	 * property is skipped, when {@code status_level} is absent or blank or
	 * matches none (never a guessed level).
	 *
	 * @param statusLevel    {@code overview_data.status_level}, or null
	 * @param sampleLevelStr {@code overview_data.sample_level_str}, or null
	 *                       when DSM did not send it (the UI defaults apply)
	 */
	static String level(String statusLevel, Map<String, String> sampleLevelStr) {
		if (statusLevel == null || statusLevel.trim().isEmpty()) return null;
		Map<String, String> levels = sampleLevelStr != null ? sampleLevelStr
				: UI_DEFAULT_LEVELS;
		for (String name : new String[] {DANGER, ATTENTION, NORMAL}) {
			if (statusLevel.equals(levels.get(name))) return name;
		}
		return null;
	}

	/** {@code "true"} or {@code "false"} for a boolean field, else {@code null} (skipped). */
	static String flag(String raw) {
		if (raw == null) return null;
		if ("true".equalsIgnoreCase(raw.trim())) return "true";
		if ("false".equalsIgnoreCase(raw.trim())) return "false";
		return null;
	}

	/** A list's size as text, or {@code null} (skipped) when DSM sent no list. */
	static String count(boolean isList, int size) {
		return isList ? String.valueOf(size) : null;
	}

	/**
	 * Review R4-W1: {@code space_affect_by_eunit_lost} says a pool is
	 * affected by a lost expansion unit. Absent is no evidence (false);
	 * {@code false} is healthy; {@code true}, or a value that is not a
	 * boolean, counts as affected (unreadable is not healthy).
	 */
	static boolean eunitLost(boolean present, String raw) {
		return present && !"false".equals(flag(raw));
	}

	/**
	 * Review R4-W1: {@code incompatible_eunit} lists expansion units DSM
	 * cannot use (their pools are not accessible). Absent is no evidence;
	 * an empty list is healthy; a non-empty list, or a value that is not a
	 * list, counts as affected.
	 */
	static boolean eunitIncompatible(boolean present, boolean isList, int size) {
		return present && (!isList || size > 0);
	}

	/**
	 * Review R4-W1: some disk's {@code used_by} names something that is
	 * neither a listed pool nor a listed shared cache. A disk in a pool
	 * names the pool ({@code reuse_1} on the lab), so a pool that left
	 * {@code storagePools} without entering {@code missing_pools} or
	 * {@code detected_pools} still shows here. An unknown value (a hot
	 * spare, a new DSM name) fails safe: pools are left out, never marked.
	 *
	 * @param diskUsedBy     one entry per disk, its {@code used_by} or null
	 *                       (null and empty name nothing)
	 * @param poolIds        {@code storagePools[].id}
	 * @param sharedCacheIds {@code sharedCaches[].id}, or null when not a list
	 */
	static boolean diskNamesUnlistedPool(List<String> diskUsedBy,
			Collection<String> poolIds, Collection<String> sharedCacheIds) {
		if (diskUsedBy == null) return false;
		for (String u : diskUsedBy) {
			if (u == null || u.isEmpty()) continue;
			if (poolIds != null && poolIds.contains(u)) continue;
			if (sharedCacheIds != null && sharedCacheIds.contains(u)) continue;
			return true;
		}
		return false;
	}
}
