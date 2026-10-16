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
 * memory.total_real = 20328576 (KB) on a 20 GB DS1520+. Run from the repo
 * root (ci/run_java_tests.sh).
 */
public final class MetricsTest {

	public static void main(String[] args) {
		memoryIsKbPublishedAsBytes();
		missingMemoryFieldIsNotZero();
		networkUsesTheTotalRowAlone();
		networkWithoutTotalSkipsVlanAndLoopback();
		networkWithBondAndNoTotalIsNotPublished();
		networkEdgeCases();
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
}
