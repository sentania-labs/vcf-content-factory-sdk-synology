package com.vcfcf.adapters.synology;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Build 32: how DSM response fields become published values, where that is
 * more than a straight copy. No VCF Ops SDK and no framework-jar
 * dependency: generic over the JSON node type through
 * {@link SynologyTopology.Json} ({@code SimpleJson} in production, maps in
 * the plain-main tests), so the tests exercise exactly the code the adapter
 * runs.
 *
 * <p>Every method returns {@code null} for a value DSM did not supply (the
 * field is missing or not a number), and the adapter then skips the key: a
 * value that was not read is never published as 0.
 */
final class SynologyMetrics {

	private SynologyMetrics() {}

	/**
	 * The node's numeric value, or {@code null} when the node is missing or
	 * not a number. DSM sends some numbers as JSON strings ({@code size.total}
	 * and friends), so a numeric string counts.
	 */
	static <J> Double num(SynologyTopology.Json<J> js, J node) {
		if (node == null || js.isNull(node)) return null;
		String s = js.str(node);
		if (s == null) return null;
		try {
			double d = Double.parseDouble(s.trim());
			return Double.isNaN(d) || Double.isInfinite(d) ? null : d;
		} catch (NumberFormatException e) {
			return null;
		}
	}

	// -----------------------------------------------------------------------
	// Issue #18: memory
	// -----------------------------------------------------------------------

	/**
	 * Issue #18: a {@code SYNO.Core.System.Utilization} memory field
	 * ({@code avail_real}, {@code total_real}, {@code cached},
	 * {@code total_swap}, {@code avail_swap}) in bytes. DSM reports these in
	 * KB (KiB); the declared unit is bytes, so the value is multiplied by
	 * 1024. Before build 32 the KB value was published as bytes, 1024 times
	 * too small.
	 */
	static <J> Double memoryBytes(SynologyTopology.Json<J> js, J memory,
			String field) {
		Double kb = num(js, js.get(memory, field));
		return kb == null ? null : kb * 1024.0;
	}

	/** Issue #18: swap in use, bytes ({@code total_swap - avail_swap}). */
	static <J> Double swapUsedBytes(SynologyTopology.Json<J> js, J memory) {
		Double total = memoryBytes(js, memory, "total_swap");
		Double avail = memoryBytes(js, memory, "avail_swap");
		return total == null || avail == null ? null : total - avail;
	}

	// -----------------------------------------------------------------------
	// Issue #18: network totals
	// -----------------------------------------------------------------------

	/**
	 * Issue #18: the NAS-wide receive and transmit rates, bytes/s, from the
	 * {@code SYNO.Core.System.Utilization} {@code network} array, as
	 * {@code {rx, tx}}, or {@code null} when they cannot be told without
	 * counting traffic twice.
	 *
	 * <ul>
	 * <li>A {@code device == "total"} row is DSM's own aggregate: it is used
	 *     alone.</li>
	 * <li>Without one, the per-interface rows are summed, leaving out
	 *     {@code lo} and a VLAN interface ({@code eth0.131}) whose parent
	 *     ({@code eth0}) is also listed, since the parent's counters already
	 *     carry its traffic.</li>
	 * <li>Without a total row and with a bond ({@code bondN}) listed, the
	 *     bond's member NICs carry the same traffic as the bond, and the
	 *     array does not say which NICs are members, so nothing is
	 *     published.</li>
	 * </ul>
	 *
	 * Before build 32 every row was summed, the total row and bond members
	 * included, so RX and TX were counted two or more times.
	 */
	static <J> double[] networkTotals(SynologyTopology.Json<J> js, J network) {
		List<J> rows = js.list(network);
		if (rows.isEmpty()) return null;
		for (J row : rows) {
			if ("total".equals(js.str(js.get(row, "device")))) {
				Double rx = num(js, js.get(row, "rx"));
				Double tx = num(js, js.get(row, "tx"));
				return rx == null || tx == null ? null : new double[] {rx, tx};
			}
		}
		Set<String> devices = new HashSet<>();
		for (J row : rows) {
			String d = js.str(js.get(row, "device"));
			if (d == null) return null;
			if (d.startsWith("bond")) return null;
			devices.add(d);
		}
		double rx = 0, tx = 0;
		int counted = 0;
		for (J row : rows) {
			String d = js.str(js.get(row, "device"));
			if ("lo".equals(d)) continue;
			int dot = d.indexOf('.');
			if (dot > 0 && devices.contains(d.substring(0, dot))) continue;
			Double r = num(js, js.get(row, "rx"));
			Double t = num(js, js.get(row, "tx"));
			if (r == null || t == null) return null;
			rx += r;
			tx += t;
			counted++;
		}
		return counted == 0 ? null : new double[] {rx, tx};
	}
}
