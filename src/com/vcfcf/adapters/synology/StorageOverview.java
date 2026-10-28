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
	 * Review R5-N3: DSM's "repair system partition" prompt, as Storage
	 * Manager shows it. {@code storage_panel.js} (DSM 7.3.2, read
	 * 2026-10-28) shows it when {@code showRepairSysPartition}:
	 * {@code !sysRAIDCrashed && !sysRAIDRebuilding &&
	 * !isLevelDanger(spaceStatusLevel) && (hasSysPartFailedDisk ||
	 * hasRootBackupFailedDisk)}, where the last two are
	 * {@code env.status.system_need_repair === true} and
	 * {@code rootbackup_need_repair === true}, {@code sysRAIDCrashed} is
	 * {@code system_crashed} and {@code sysRAIDRebuilding} is
	 * {@code system_rebuilding || rootbackup_rebuilding}. So {@code "true"}
	 * when either repair flag is true and none of crashed, rebuilding or a
	 * Critical storage level holds (those have their own rows, and the
	 * crashed and Critical ones their own symptoms); {@code "false"} when
	 * a repair flag is true but one of them holds, or when
	 * {@code system_need_repair} is {@code false} and the root backup flag
	 * is not true ({@code rootbackup_need_repair} is absent on the lab
	 * DS1520+, which the UI reads as not needing repair); {@code null}
	 * (skipped) when no repair flag is true and {@code system_need_repair}
	 * is not a boolean. A suppressor DSM did not send does not suppress,
	 * as in the UI. The lab sends {@code system_crashed},
	 * {@code system_need_repair} and {@code system_rebuilding}, all false.
	 *
	 * @param storageLevel {@link #level}'s result for the same response, or null
	 */
	static String systemPartitionNeedsRepair(String systemNeedRepair,
			String rootbackupNeedRepair, String systemCrashed,
			String systemRebuilding, String rootbackupRebuilding,
			String storageLevel) {
		boolean needsRepair = "true".equals(flag(systemNeedRepair))
				|| "true".equals(flag(rootbackupNeedRepair));
		if (!needsRepair) {
			return "false".equals(flag(systemNeedRepair)) ? "false" : null;
		}
		boolean shownElsewhere = "true".equals(flag(systemCrashed))
				|| "true".equals(flag(systemRebuilding))
				|| "true".equals(flag(rootbackupRebuilding))
				|| DANGER.equals(storageLevel);
		return shownElsewhere ? "false" : "true";
	}

	/**
	 * DSM's {@code used_by} for a hot spare disk: {@code storage_panel.js}
	 * {@code isHotspare(){return "hotspare"===this.used_by}} and
	 * {@code notUsedBySpace()} (empty or {@code hotspare}). Not seen live:
	 * the lab NAS has no hot spare.
	 */
	static final String HOT_SPARE = "hotspare";

	/**
	 * Review R4-W1: some disk's {@code used_by} names something that is
	 * neither a listed pool, a listed shared cache, a listed SSD cache nor
	 * a hot spare. A disk in a pool names the pool ({@code reuse_1} on the
	 * lab), so a pool that left {@code storagePools} without entering
	 * {@code missing_pools} or {@code detected_pools} still shows here. An
	 * unknown value (a new DSM name, {@code system}) fails safe: pools are
	 * left out, never marked.
	 *
	 * <p>Review R5-N4: a disk in a non-shared SSD cache names the cache. DSM
	 * parses both the disk's {@code used_by} and a single cache's id as a
	 * space id of type {@code ssd} ({@code storage_panel.js}
	 * {@code isSingleCache}, {@code relatedPoolArr}), so a {@code used_by}
	 * equal to a listed {@code ssdCaches[].id} is accepted; that format is
	 * from DSM's code, not seen live (the lab's only cache is shared:
	 * disks name {@code shared_cache_1}, the cache is
	 * {@code alloc_cache_1_1}), and a value that is not exactly a listed
	 * cache id still keeps pool removal off. A hot spare's
	 * {@link #HOT_SPARE} names no space.
	 *
	 * @param diskUsedBy     one entry per disk, its {@code used_by} or null
	 *                       (null and empty name nothing)
	 * @param poolIds        {@code storagePools[].id}
	 * @param sharedCacheIds {@code sharedCaches[].id}, or null when not a list
	 * @param ssdCacheIds    {@code ssdCaches[].id}, or null
	 */
	static boolean diskNamesUnlistedPool(List<String> diskUsedBy,
			Collection<String> poolIds, Collection<String> sharedCacheIds,
			Collection<String> ssdCacheIds) {
		if (diskUsedBy == null) return false;
		for (String u : diskUsedBy) {
			if (u == null || u.isEmpty()) continue;
			if (HOT_SPARE.equals(u)) continue;
			if (poolIds != null && poolIds.contains(u)) continue;
			if (sharedCacheIds != null && sharedCacheIds.contains(u)) continue;
			if (ssdCacheIds != null && ssdCacheIds.contains(u)) continue;
			return true;
		}
		return false;
	}
}
