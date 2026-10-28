package com.vcfcf.adapters.synology;

import java.util.ArrayList;
import java.util.List;

/**
 * Build 35 (review B2): DSM's System Health verdict
 * ({@code SYNO.Core.System.SystemHealth get}) as DiskStation properties, and
 * the fan health derived from it. No VCF Ops SDK and no framework-jar
 * dependency (generic over the JSON node type), so the plain-main tests run
 * exactly this code.
 *
 * <p><b>Why here.</b> {@code SYNO.Core.Hardware.FanSpeed} {@code cool_fan}
 * says which fan speed modes the model offers, not whether a fan works (DSM's
 * Fan Speed Mode panel, admin_center.js). On a desktop model such as the
 * DS1520+ (DSM 7.3.2, checked 2026-10-28) no polled API carries a per-fan
 * state: not FanSpeed, {@code SYNO.Core.System info}, {@code
 * SYNO.Core.System.Status}, {@code SYNO.Storage.CGI.Storage load_info} (no
 * fans in {@code env.storageMachineInfo}) nor {@code
 * SYNO.Storage.CGI.Enclosure} (rack and expansion enclosures only; error 103
 * on this model). DSM's own UI shows a failed fan in the System Health
 * widget (webman/modules/SystemInfoApp/SystemHealthWidget.js), which polls
 * this API and renders {@code rule.description} through the {@code widget}
 * string table. That table's fan entries are {@code widget:fan_fail} ("The
 * fan of your DiskStation has failed"), {@code widget:cpu_fan_fail} and
 * {@code widget:eunit_fan_fail}; a healthy NAS answers {@code rule.type} 2
 * with {@code widget:system_ok}.
 *
 * <p><b>Limit.</b> The API returns one rule, the one DSM ranks highest. While
 * another problem outranks a fan failure the fan is not visible, so the fan
 * health is then {@code unknown}, never {@code normal}. Not yet seen with a
 * failed fan (the lab NAS has none).
 */
final class SystemHealth {

	private SystemHealth() {}

	static final String FAN_FAILED = "failed";
	static final String FAN_NORMAL = "normal";
	static final String FAN_UNKNOWN = "unknown";

	/**
	 * DSM's level for {@code rule.type} as the widget's RuleMappingUtil
	 * defines it (0 danger, 1 attention, 2 normal), or {@code null} when the
	 * rule or its type is missing or another number.
	 */
	static <J> String level(SynologyTopology.Json<J> js, J rule) {
		if (rule == null || js.isNull(rule)) return null;
		Double t = SynologyMetrics.num(js, js.get(rule, "type"));
		if (t == null) return null;
		switch (t.intValue()) {
			case 0:
				return "danger";
			case 1:
				return "attention";
			case 2:
				return "normal";
			default:
				return null;
		}
	}

	/** The rule's id ({@code system_health}, {@code ups_status_abnormal}, ...), or {@code null}. */
	static <J> String ruleId(SynologyTopology.Json<J> js, J rule) {
		if (rule == null || js.isNull(rule)) return null;
		return SynologyMetrics.text(js, js.get(rule, "id"));
	}

	/**
	 * The string-table keys of the rule's message, {@code section:key}, as
	 * the widget resolves them: with {@code description_use_formatted} the
	 * format itself is the key, otherwise each parameter is one. Empty when
	 * the rule has no description.
	 */
	static <J> List<String> messageKeys(SynologyTopology.Json<J> js, J rule) {
		List<String> out = new ArrayList<>();
		if (rule == null || js.isNull(rule)) return out;
		J d = js.get(rule, "description");
		if (d == null || js.isNull(d)) return out;
		if (js.bool(js.get(d, "description_use_formatted"))) {
			String f = SynologyMetrics.text(js, js.get(d, "description_format"));
			if (f != null) out.add(f);
			return out;
		}
		for (J p : js.list(js.get(d, "description_params"))) {
			String k = SynologyMetrics.text(js, p);
			if (k != null) out.add(k);
		}
		return out;
	}

	/** The message keys joined with {@code ", "}, or {@code null} when there are none. */
	static <J> String message(SynologyTopology.Json<J> js, J rule) {
		List<String> k = messageKeys(js, rule);
		return k.isEmpty() ? null : String.join(", ", k);
	}

	/**
	 * Fan health from the rule: {@code failed} when a message key is a fan
	 * failure ({@code fan_fail}, {@code cpu_fan_fail}, {@code eunit_fan_fail}),
	 * {@code normal} when DSM reports the system healthy (type 2), else
	 * {@code unknown} (another problem outranks the fans, so their state is
	 * not visible). {@code null}, so the property is skipped, when the rule
	 * was not read or has no level.
	 */
	static <J> String fanHealth(SynologyTopology.Json<J> js, J rule) {
		String level = level(js, rule);
		if (level == null) return null;
		for (String k : messageKeys(js, rule)) {
			String key = k.substring(k.indexOf(':') + 1);
			if (key.endsWith("fan_fail")) return FAN_FAILED;
		}
		return "normal".equals(level) ? FAN_NORMAL : FAN_UNKNOWN;
	}
}
