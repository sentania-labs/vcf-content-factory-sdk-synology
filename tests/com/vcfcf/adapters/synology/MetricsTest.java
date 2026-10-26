package com.vcfcf.adapters.synology;

import static com.vcfcf.adapters.synology.TopologyTest.MAPS;
import static com.vcfcf.adapters.synology.TopologyTest.obj;

import java.util.Arrays;
import java.util.Collections;
import java.util.Map;

/**
 * Build 32: DSM fields to published values (SynologyMetrics). Fixture
 * values are those of the factory API map (knowledge/context/api-maps/
 * synology-system.md) and the devel golden baseline, where DSM sent
 * memory.total_real = 20328576 (KB) on a 20 GB DS1520+. Issue #34 fixtures
 * are the verbatim DSM 7.3.2 values in knowledge/context/api-maps/
 * synology-storage.md (volume and pool size, disk remain_life) and
 * synology-iscsi.md (the Utilization lun[] row). Issue #41 fixtures are the
 * Utilization cpu object of synology-system.md and the DSM values recorded
 * in the build 32 devel_9_1 proof (knowledge/context/investigations/
 * 2026-10-16-synology-32-devel-9-1-proof.md, item 4). Run from the repo root
 * (ci/run_java_tests.sh).
 */
public final class MetricsTest {

	public static void main(String[] args) {
		memoryIsKbPublishedAsBytes();
		missingMemoryFieldIsNotZero();
		networkUsesTheTotalRowAlone();
		networkWithoutTotalSkipsVlanAndLoopback();
		networkWithBondAndNoTotalIsNotPublished();
		networkEdgeCases();
		ovsBridgeIsNotCountedTwice();
		anyBondNameWithoutTotalIsNotPublished();
		memoryUsageNeedsATotal();
		volumeFreeIsTotalMinusUsed();
		poolIsAllocationAsDsmReports();
		capacityWithoutTotalPublishesNoUsage();
		remainLifeReadsTheObjectValue();
		lunLatencyUsesTheAvgFields();
		loadAveragesAreDividedBy100();
		System.out.println("MetricsTest: all assertions passed");
	}

	static Map<String, Object> memory() {
		return obj("total_real", 20328576, "avail_real", 214256,
				"real_usage", 22, "cached", "8388608",
				"total_swap", 20971520, "avail_swap", 20447232);
	}

	static void memoryIsKbPublishedAsBytes() {
		Map<String, Object> m = memory();
		T.eq(20328576.0 * 1024, SynologyMetrics.memoryBytes(MAPS, m, "total_real"),
				"total_real KB to bytes");
		T.eq(214256.0 * 1024, SynologyMetrics.memoryBytes(MAPS, m, "avail_real"),
				"avail_real KB to bytes");
		T.eq(8388608.0 * 1024, SynologyMetrics.memoryBytes(MAPS, m, "cached"),
				"a numeric string counts");
		T.eq((20971520.0 - 20447232.0) * 1024,
				SynologyMetrics.swapUsedBytes(MAPS, m), "swap used bytes");
		// About 19.4 GiB for a 20 GB NAS, not 19 MiB.
		double gib = SynologyMetrics.memoryBytes(MAPS, m, "total_real")
				/ (1024.0 * 1024 * 1024);
		T.check(gib > 19 && gib < 20, "plausible total: " + gib + " GiB");
	}

	static void missingMemoryFieldIsNotZero() {
		Map<String, Object> m = obj("total_real", 1, "total_swap", 5,
				"cached", "n/a");
		T.eq(null, SynologyMetrics.memoryBytes(MAPS, m, "avail_real"),
				"missing field is null, not 0");
		T.eq(null, SynologyMetrics.memoryBytes(MAPS, m, "cached"),
				"non-numeric field is null");
		T.eq(null, SynologyMetrics.swapUsedBytes(MAPS, m),
				"swap used needs both fields");
		T.eq(null, SynologyMetrics.memoryBytes(MAPS, null, "total_real"),
				"no memory object");
	}

	static void networkUsesTheTotalRowAlone() {
		Object net = Arrays.asList(
				obj("device", "eth0", "rx", 1000, "tx", 100),
				obj("device", "eth1", "rx", 2000, "tx", 200),
				obj("device", "bond0", "rx", 3000, "tx", 300),
				obj("device", "total", "rx", 3000, "tx", 300));
		double[] t = SynologyMetrics.networkTotals(MAPS, net);
		T.eq(3000.0, t[0], "rx from the total row only");
		T.eq(300.0, t[1], "tx from the total row only");
	}

	static void networkWithoutTotalSkipsVlanAndLoopback() {
		Object net = Arrays.asList(
				obj("device", "eth0", "rx", 1000, "tx", 100),
				obj("device", "eth0.131", "rx", 400, "tx", 40),
				obj("device", "eth1", "rx", 2000, "tx", 200),
				obj("device", "lo", "rx", 50, "tx", 50));
		double[] t = SynologyMetrics.networkTotals(MAPS, net);
		T.eq(3000.0, t[0], "rx: physical NICs only");
		T.eq(300.0, t[1], "tx: physical NICs only");
		// The API map's single-row shape: a VLAN whose parent is not listed
		// is the interface itself and is counted.
		double[] one = SynologyMetrics.networkTotals(MAPS, Arrays.asList(
				obj("device", "eth0.131", "rx", 123456, "tx", 654321)));
		T.eq(123456.0, one[0], "lone VLAN row counted");
	}

	static void networkWithBondAndNoTotalIsNotPublished() {
		Object net = Arrays.asList(
				obj("device", "eth0", "rx", 1000, "tx", 100),
				obj("device", "eth1", "rx", 2000, "tx", 200),
				obj("device", "bond0", "rx", 3000, "tx", 300));
		T.eq(null, SynologyMetrics.networkTotals(MAPS, net),
				"bond members unknown: not published");
	}

	static void networkEdgeCases() {
		T.eq(null, SynologyMetrics.networkTotals(MAPS, Collections.emptyList()),
				"empty array");
		T.eq(null, SynologyMetrics.networkTotals(MAPS, null), "no array");
		T.eq(null, SynologyMetrics.networkTotals(MAPS, Arrays.asList(
				obj("device", "total", "rx", 5))), "total row without tx");
		T.eq(null, SynologyMetrics.networkTotals(MAPS, Arrays.asList(
				obj("device", "eth0", "rx", 5, "tx", 6), obj("rx", 1, "tx", 1))),
				"a row without a device name");
		T.eq(null, SynologyMetrics.networkTotals(MAPS, Arrays.asList(
				obj("device", "lo", "rx", 5, "tx", 6))), "loopback only");
	}

	static void volumeFreeIsTotalMinusUsed() {
		// DSM 7.3.2 volume size: strings, no "free" member.
		SynologyMetrics.Capacity c = SynologyMetrics.volumeCapacity(MAPS,
				obj("free_inode", "0", "total", "28788160495616",
						"total_device", "29987667181568", "total_inode", "0",
						"used", "7632707117056"));
		T.eq(28788160495616.0, c.total, "volume total");
		T.eq(28788160495616.0 - 7632707117056.0, c.free, "free = total - used");
		T.check(Math.abs(c.usagePct - 26.513) < 0.01, "usage about 26.5 %: " + c.usagePct);
		// A DSM that does send free: it is used as is.
		SynologyMetrics.Capacity f = SynologyMetrics.volumeCapacity(MAPS,
				obj("total", 100, "free", 40));
		T.eq(40.0, f.free, "free as sent");
		T.eq(60.0, f.usagePct, "usage from free");
	}

	static void poolIsAllocationAsDsmReports() {
		SynologyMetrics.Capacity c = SynologyMetrics.poolCapacity(MAPS,
				obj("total", "29987679764480", "used", "29987679764480"));
		T.eq(29987679764480.0, c.total, "pool total");
		T.eq(29987679764480.0, c.used, "pool used = allocated");
		T.eq(100.0, c.usagePct, "a fully allocated pool reads 100 %");
	}

	static void capacityWithoutTotalPublishesNoUsage() {
		SynologyMetrics.Capacity none = SynologyMetrics.volumeCapacity(MAPS, null);
		T.eq(null, none.total, "no size: no total");
		T.eq(null, none.free, "no size: no free");
		T.eq(null, none.usagePct, "no size: no usage, not 0");
		SynologyMetrics.Capacity zero = SynologyMetrics.poolCapacity(MAPS,
				obj("total", "0", "used", "0"));
		T.eq(null, zero.usagePct, "zero total: no usage, not 0");
		SynologyMetrics.Capacity onlyTotal = SynologyMetrics.volumeCapacity(MAPS,
				obj("total", "100"));
		T.eq(100.0, onlyTotal.total, "total alone is published");
		T.eq(null, onlyTotal.free, "free unknown");
		T.eq(null, onlyTotal.usagePct, "usage unknown");
	}

	static void remainLifeReadsTheObjectValue() {
		T.eq(81.0, SynologyMetrics.remainLife(MAPS,
				obj("trustable", true, "value", 81)), "NVMe SSD 81 %");
		T.eq(null, SynologyMetrics.remainLife(MAPS,
				obj("trustable", true, "value", -1)), "HDD -1 is not published");
		T.eq(null, SynologyMetrics.remainLife(MAPS,
				obj("trustable", false, "value", 50)), "untrusted is not published");
		T.eq(79.0, SynologyMetrics.remainLife(MAPS, obj("value", "79")),
				"no trustable member: the value counts");
		T.eq(90.0, SynologyMetrics.remainLife(MAPS, 90), "older DSM plain number");
		T.eq(null, SynologyMetrics.remainLife(MAPS, null), "absent");
		T.eq(null, SynologyMetrics.remainLife(MAPS, obj("trustable", true)),
				"object without value");
	}

	static void lunLatencyUsesTheAvgFields() {
		Map<String, Object> row = obj("uuid", "d023e190", "read_iops", 0,
				"write_iops", 164, "read_throughput", 0, "write_throughput", 867388,
				"read_avg_latency", 0, "write_avg_latency", 71, "total_iops", 164,
				"total_throughput", 867388, "total_io_latency", 66);
		T.eq(0.0, SynologyMetrics.lunLatency(MAPS, row, "read_avg_latency",
				"read_latency"), "read latency, a real 0");
		T.eq(71.0, SynologyMetrics.lunLatency(MAPS, row, "write_avg_latency",
				"write_latency"), "write latency from write_avg_latency");
		T.eq(5.0, SynologyMetrics.lunLatency(MAPS, obj("read_latency", 5),
				"read_avg_latency", "read_latency"), "old field name still read");
		T.eq(null, SynologyMetrics.lunLatency(MAPS, obj("read_iops", 3),
				"read_avg_latency", "read_latency"), "absent: not published");
	}

	/** Review N5: ovs_eth0 beside eth0 carries the same traffic. */
	static void ovsBridgeIsNotCountedTwice() {
		double[] t = SynologyMetrics.networkTotals(MAPS, java.util.Arrays.asList(
				obj("device", "eth0", "rx", 1000, "tx", 100),
				obj("device", "ovs_eth0", "rx", 1000, "tx", 100),
				obj("device", "eth1", "rx", 500, "tx", 50)));
		T.eq(1500.0, t[0], "ovs bridge skipped when its member is listed");
		double[] lone = SynologyMetrics.networkTotals(MAPS, java.util.Arrays.asList(
				obj("device", "ovs_eth0", "rx", 700, "tx", 70)));
		T.eq(700.0, lone[0], "a lone ovs bridge is the interface itself");
	}

	/** Review N7: memory_usage_pct needs a positive total_real. */
	static void memoryUsageNeedsATotal() {
		T.eq(22.0, SynologyMetrics.memoryUsagePct(MAPS, memory()), "as DSM sends it");
		T.eq(null, SynologyMetrics.memoryUsagePct(MAPS, obj("real_usage", 22)),
				"no total: not published");
		T.eq(null, SynologyMetrics.memoryUsagePct(MAPS,
				obj("total_real", 0, "real_usage", 0)), "zero total: not published");
		T.eq(null, SynologyMetrics.memoryUsagePct(MAPS, obj("total_real", 5)),
				"no real_usage: not published, not 0");
	}

	/** Peer review: ovs_bond0 without a listed bond0 is still a bond. */
	static void anyBondNameWithoutTotalIsNotPublished() {
		T.eq(null, SynologyMetrics.networkTotals(MAPS, java.util.Arrays.asList(
				obj("device", "eth0", "rx", 200, "tx", 200),
				obj("device", "eth1", "rx", 200, "tx", 200),
				obj("device", "ovs_bond0", "rx", 400, "tx", 400))),
				"ovs_bond0 without a total row: not published");
		double[] t = SynologyMetrics.networkTotals(MAPS, java.util.Arrays.asList(
				obj("device", "ovs_bond0", "rx", 400, "tx", 400),
				obj("device", "total", "rx", 400, "tx", 400)));
		T.eq(400.0, t[0], "with a total row the total is used");
	}

	/**
	 * Issue #41: DSM's load averages are x100. Recorded values: the API map's
	 * Utilization cpu object, and the build 32 devel_9_1 proof, 473 / 280 /
	 * 138 at 3:33 PM (98 % CPU, 4-core DS1520+) and 68 / 215 / 158 at
	 * 3:38 PM.
	 */
	static void loadAveragesAreDividedBy100() {
		Map<String, Object> apiMap = obj("user_load", 5, "system_load", 3,
				"other_load", 0, "1min_load", 12, "5min_load", 8, "15min_load", 6);
		T.eq(0.12, SynologyMetrics.loadAverage(MAPS, apiMap, "1min_load"), "API map 1 min");
		T.eq(0.08, SynologyMetrics.loadAverage(MAPS, apiMap, "5min_load"), "API map 5 min");
		T.eq(0.06, SynologyMetrics.loadAverage(MAPS, apiMap, "15min_load"), "API map 15 min");
		Map<String, Object> busy = obj("1min_load", 473, "5min_load", 280,
				"15min_load", 138);
		T.eq(4.73, SynologyMetrics.loadAverage(MAPS, busy, "1min_load"),
				"proof peak: 473 is a load of 4.73");
		T.eq(2.8, SynologyMetrics.loadAverage(MAPS, busy, "5min_load"), "proof 5 min");
		T.eq(1.38, SynologyMetrics.loadAverage(MAPS, busy, "15min_load"), "proof 15 min");
		Map<String, Object> later = obj("1min_load", 68, "5min_load", 215,
				"15min_load", 158);
		T.eq(0.68, SynologyMetrics.loadAverage(MAPS, later, "1min_load"), "proof 3:38 PM 1 min");
		T.eq(2.15, SynologyMetrics.loadAverage(MAPS, later, "5min_load"), "proof 3:38 PM 5 min");
		T.eq(1.58, SynologyMetrics.loadAverage(MAPS, later, "15min_load"), "proof 3:38 PM 15 min");
		T.eq(0.0, SynologyMetrics.loadAverage(MAPS, obj("1min_load", 0), "1min_load"),
				"an idle NAS: a real 0");
		T.eq(1.5, SynologyMetrics.loadAverage(MAPS, obj("1min_load", "150"), "1min_load"),
				"a numeric string counts");
		T.eq(null, SynologyMetrics.loadAverage(MAPS, obj("user_load", 5), "1min_load"),
				"absent: not published, not 0");
		T.eq(null, SynologyMetrics.loadAverage(MAPS, null, "1min_load"), "no cpu object");
	}
}
