package com.vcfcf.adapters.synology;

import static com.vcfcf.adapters.synology.TopologyTest.MAPS;
import static com.vcfcf.adapters.synology.TopologyTest.list;
import static com.vcfcf.adapters.synology.TopologyTest.obj;

import java.util.Arrays;
import java.util.Map;

/**
 * Build 35 (review B2, W1): DSM's System Health rule to properties and fan
 * health (SystemHealth), and present-only status text
 * (SynologyMetrics.text). The healthy fixture is the verbatim
 * SYNO.Core.System.SystemHealth rule from the lab DS1520+ (DSM 7.3.2,
 * 2026-10-28); the failure fixtures use the widget string-table keys
 * (webman/texts/enu/strings [widget]) with the rule shape of the live one,
 * since the lab has no failed fan. Run from the repo root
 * (ci/run_java_tests.sh).
 */
public final class SystemHealthTest {

	public static void main(String[] args) {
		healthyNasIsNormal();
		fanFailureIsFailed();
		anotherProblemHidesTheFans();
		missingRuleIsSkipped();
		formattedDescriptionUsesTheFormat();
		blankStatusIsNotPublished();
		System.out.println("SystemHealthTest: all assertions passed");
	}

	static Map<String, Object> rule(String id, int type, String... params) {
		return obj("id", id, "priority", 40, "type", type,
				"description", obj("description_format", "{0}",
						"description_params", list((Object[]) params),
						"description_use_formatted", false));
	}

	static void healthyNasIsNormal() {
		Map<String, Object> r = rule("system_health", 2, "widget:system_ok");
		T.eq("normal", SystemHealth.level(MAPS, r), "type 2 is normal");
		T.eq("system_health", SystemHealth.ruleId(MAPS, r), "rule id");
		T.eq("widget:system_ok", SystemHealth.message(MAPS, r), "message key");
		T.eq("normal", SystemHealth.fanHealth(MAPS, r), "healthy NAS: fans normal");
	}

	static void fanFailureIsFailed() {
		for (String k : new String[] {"widget:fan_fail", "widget:cpu_fan_fail",
				"widget:eunit_fan_fail"}) {
			Map<String, Object> r = rule("system_health", 0, k);
			T.eq("danger", SystemHealth.level(MAPS, r), k + ": level");
			T.eq("failed", SystemHealth.fanHealth(MAPS, r), k + ": fan failed");
		}
		T.eq("failed", SystemHealth.fanHealth(MAPS,
				rule("system_health", 1, "widget:fan_fail")), "any level");
	}

	static void anotherProblemHidesTheFans() {
		Map<String, Object> r = rule("ups_status_abnormal", 1,
				"widget:usb_ups_status_battery");
		T.eq("attention", SystemHealth.level(MAPS, r), "type 1 is attention");
		T.eq("unknown", SystemHealth.fanHealth(MAPS, r),
				"an outranking problem: fan state unknown, never normal");
		T.eq("unknown", SystemHealth.fanHealth(MAPS,
				rule("storage_is_attention", 0, "widget:volume_degraded")),
				"danger from storage: unknown");
	}

	static void missingRuleIsSkipped() {
		T.eq(null, SystemHealth.level(MAPS, null), "no rule: no level");
		T.eq(null, SystemHealth.fanHealth(MAPS, null), "no rule: no fan health");
		T.eq(null, SystemHealth.message(MAPS, null), "no rule: no message");
		T.eq(null, SystemHealth.fanHealth(MAPS, obj("id", "x")),
				"no type: skipped");
		T.eq(null, SystemHealth.level(MAPS, obj("type", 7)), "unknown type: skipped");
	}

	static void formattedDescriptionUsesTheFormat() {
		Map<String, Object> r = obj("id", "volume_usage", "type", 1,
				"description", obj("description_format", "widget:available_volume_space_warning",
						"description_params", list("Volume 1"),
						"description_use_formatted", true));
		T.eq(Arrays.asList("widget:available_volume_space_warning"),
				SystemHealth.messageKeys(MAPS, r), "the format is the key");
	}

	static void blankStatusIsNotPublished() {
		T.eq(null, SynologyMetrics.text(MAPS, null), "missing");
		T.eq(null, SynologyMetrics.text(MAPS, ""), "empty");
		T.eq(null, SynologyMetrics.text(MAPS, "  "), "blank");
		T.eq("normal", SynologyMetrics.text(MAPS, "normal"), "present");
		T.eq("unknown", SynologyMetrics.text(MAPS, "unknown"),
				"SMART unknown is DSM's value and is published as sent");
	}
}
