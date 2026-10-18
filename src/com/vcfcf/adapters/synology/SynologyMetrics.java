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

	/**
	 * Review N7 on build 32: DSM's {@code real_usage} percent, or
	 * {@code null} when it is absent or {@code total_real} is missing or 0
	 * (a hollow memory object would otherwise publish 0 %).
	 */
	static <J> Double memoryUsagePct(SynologyTopology.Json<J> js, J memory) {
		Double total = num(js, js.get(memory, "total_real"));
		if (total == null || total <= 0) return null;
		return num(js, js.get(memory, "real_usage"));
	}

	/** Issue #18: swap in use, bytes ({@code total_swap - avail_swap}). */
	static <J> Double swapUsedBytes(SynologyTopology.Json<J> js, J memory) {
		Double total = memoryBytes(js, memory, "total_swap");
		Double avail = memoryBytes(js, memory, "avail_swap");
		return total == null || avail == null ? null : total - avail;
	}

	// -----------------------------------------------------------------------
	// Issue #41: load averages
	// -----------------------------------------------------------------------

	/**
	 * Issue #41: a {@code SYNO.Core.System.Utilization} load average
	 * ({@code cpu.1min_load}, {@code 5min_load}, {@code 15min_load}) as the
	 * Unix run-queue length, or {@code null} when DSM did not send it. DSM
	 * scales the load averages by 100, so the value is divided by 100. The
	 * scale is derived, not compared against /proc/loadavg: the build 32
	 * devel_9_1 proof (factory knowledge/context/investigations/
	 * 2026-10-16-synology-32-devel-9-1-proof.md, item 4) saw 473 / 280 / 138
	 * on a 4-core DS1520+ at 98 % CPU, a real 1-minute load of 4.73. Builds
	 * up to 32 published DSM's value as sent, 100 times too large, and a
	 * missing field as 0.
	 */
	static <J> Double loadAverage(SynologyTopology.Json<J> js, J cpu,
			String field) {
		Double scaled = num(js, js.get(cpu, field));
		return scaled == null ? null : scaled / 100.0;
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
	 *     {@code lo}, a VLAN interface ({@code eth0.131}) whose parent
	 *     ({@code eth0}) is also listed, and an Open vSwitch bridge
	 *     ({@code ovs_eth0}) whose member is listed, since those counters
	 *     already carry the same traffic.</li>
	 * <li>Without a total row and with a bond listed (any name containing
	 *     {@code bond}: {@code bond0}, {@code ovs_bond0}), the
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
			// Peer review: any bond (bond0, ovs_bond0) hides which NICs are
			// its members, so without a total row nothing is published.
			if (d.contains("bond")) return null;
			devices.add(d);
		}
		double rx = 0, tx = 0;
		int counted = 0;
		for (J row : rows) {
			String d = js.str(js.get(row, "device"));
			if ("lo".equals(d)) continue;
			int dot = d.indexOf('.');
			if (dot > 0 && devices.contains(d.substring(0, dot))) continue;
			// Review N5: an Open vSwitch bridge (ovs_eth0, created by Virtual
			// Machine Manager) carries its member's traffic again.
			if (d.startsWith("ovs_") && devices.contains(d.substring(4))) continue;
			Double r = num(js, js.get(row, "rx"));
			Double t = num(js, js.get(row, "tx"));
			if (r == null || t == null) return null;
			rx += r;
			tx += t;
			counted++;
		}
		return counted == 0 ? null : new double[] {rx, tx};
	}

	// -----------------------------------------------------------------------
	// Issue #34: capacity, remaining life, LUN latency
	// -----------------------------------------------------------------------

	/** Issue #34: one object's capacity values; a field is null when not known. */
	static final class Capacity {
		final Double total;
		final Double used;
		final Double free;
		final Double usagePct;

		Capacity(Double total, Double used, Double free) {
			this.total = total;
			this.used = used;
			this.free = free;
			this.usagePct = (total != null && total > 0 && used != null)
					? used / total * 100.0 : null;
		}
	}

	/**
	 * Issue #34: a storage pool's {@code size} object. DSM sends
	 * {@code total} and {@code used} (strings). For a pool, {@code used} is
	 * the space allocated to volumes, so a pool fully allocated to its
	 * volume reads used == total (100 %) whatever the volumes hold; that is
	 * DSM's answer, published as is (display names say "Allocated").
	 * Volume fill level is the Volume's capacity.
	 */
	static <J> Capacity poolCapacity(SynologyTopology.Json<J> js, J size) {
		Double total = num(js, js.get(size, "total"));
		Double used = num(js, js.get(size, "used"));
		return new Capacity(total, used, null);
	}

	/**
	 * Issue #34: a volume's {@code size} object. DSM 7.x sends
	 * {@code total}, {@code used} and {@code total_device}, and no
	 * {@code free} (factory API map, DSM 7.3.2): before build 32 the
	 * adapter read the missing {@code size.free}, published 0 and so 100 %
	 * used for every volume. Free is now {@code size.free} when DSM sends
	 * it, else {@code total - used}; usage is used / total.
	 */
	static <J> Capacity volumeCapacity(SynologyTopology.Json<J> js, J size) {
		Double total = num(js, js.get(size, "total"));
		Double used = num(js, js.get(size, "used"));
		Double free = num(js, js.get(size, "free"));
		if (free == null && total != null && used != null) {
			free = Math.max(0.0, total - used);
		}
		if (used == null && total != null && free != null) {
			used = Math.max(0.0, total - free);
		}
		return new Capacity(total, used, free);
	}

	/**
	 * Issue #34: a disk's remaining life, percent. DSM 7.x sends
	 * {@code remain_life} as an object {@code {trustable, value}} (factory
	 * API map, DSM 7.3.2): before build 32 the adapter read the object as a
	 * number and published 0 for every disk. A plain number (older DSM) is
	 * read as is. {@code value < 0} (DSM's -1 for HDDs, which report no
	 * wear) and {@code trustable == false} are not published.
	 */
	static <J> Double remainLife(SynologyTopology.Json<J> js, J node) {
		if (node == null || js.isNull(node)) return null;
		Double v = num(js, node);
		if (v == null) {
			J trustable = js.get(node, "trustable");
			if (trustable != null && !js.isNull(trustable) && !js.bool(trustable)) {
				return null;
			}
			v = num(js, js.get(node, "value"));
		}
		return v == null || v < 0 ? null : v;
	}

	/**
	 * Issue #34: an iSCSI LUN latency from its
	 * {@code SYNO.Core.System.Utilization} {@code lun[]} row. DSM sends
	 * {@code read_avg_latency} and {@code write_avg_latency} (factory API
	 * map); before build 32 the adapter read {@code read_latency} and
	 * {@code write_latency}, which DSM does not send, and published 0. The
	 * old names are still read if present.
	 *
	 * @param avgKey    {@code read_avg_latency} or {@code write_avg_latency}
	 * @param legacyKey {@code read_latency} or {@code write_latency}
	 */
	static <J> Double lunLatency(SynologyTopology.Json<J> js, J io,
			String avgKey, String legacyKey) {
		Double v = num(js, js.get(io, avgKey));
		return v != null ? v : num(js, js.get(io, legacyKey));
	}
}
