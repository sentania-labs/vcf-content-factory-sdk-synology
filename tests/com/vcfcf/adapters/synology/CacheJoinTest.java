package com.vcfcf.adapters.synology;

import static com.vcfcf.adapters.synology.TopologyTest.MAPS;
import static com.vcfcf.adapters.synology.TopologyTest.edge;
import static com.vcfcf.adapters.synology.TopologyTest.fullFixture;
import static com.vcfcf.adapters.synology.TopologyTest.list;
import static com.vcfcf.adapters.synology.TopologyTest.o;
import static com.vcfcf.adapters.synology.TopologyTest.obj;
import static com.vcfcf.adapters.synology.TopologyTest.response;
import static com.vcfcf.adapters.synology.TopologyTest.run;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Build 32 (issue #25): the Volume's cache metrics, the SSD cache display
 * name and the Volume to SSD cache edge use one join: mountSpaceId ==
 * volume id, path == vol_path only when the cache has no mountSpaceId.
 * The API map fixture (knowledge/context/api-maps/synology-storage.md)
 * carries both, mountSpaceId "volume_1" and path "/volume1". Run from the
 * repo root (ci/run_java_tests.sh).
 */
public final class CacheJoinTest {

	public static void main(String[] args) {
		mountSpaceIdWinsWhenTheyDisagree();
		pathOnlyWhenMountSpaceIdIsAbsent();
		noJoinKeyNoJoin();
		metricsAndEdgeAgree();
		System.out.println("CacheJoinTest: all assertions passed");
	}

	static Map<String, Object> vol1() {
		return obj("id", "volume_1", "num_id", 1, "vol_path", "/volume1");
	}

	static Map<String, Object> vol2() {
		return obj("id", "volume_2", "num_id", 2, "vol_path", "/volume2");
	}

	static Object storage(Object... caches) {
		return response(obj("volumes", list(vol1(), vol2()),
				"ssdCaches", list(caches)));
	}

	static void mountSpaceIdWinsWhenTheyDisagree() {
		Map<String, Object> cache = obj("id", "c1", "mountSpaceId", "volume_2",
				"path", "/volume1");
		Object st = storage(cache);
		T.check(SynologyTopology.cacheForVolume(MAPS, vol2(), st) == cache,
				"volume 2 gets the cache by mountSpaceId");
		T.eq(null, SynologyTopology.cacheForVolume(MAPS, vol1(), st),
				"volume 1 does not get it by path");
		T.eq("SSD Cache (Volume 2)", SynologyTopology.cacheName(MAPS, cache, st),
				"name follows the same join");
		// A mountSpaceId that matches no volume is no join, never a path match.
		Map<String, Object> stray = obj("id", "c9", "mountSpaceId", "volume_7",
				"path", "/volume1");
		T.eq(null, SynologyTopology.cacheForVolume(MAPS, vol1(), storage(stray)),
				"no fallback to path when mountSpaceId is present");
	}

	static void pathOnlyWhenMountSpaceIdIsAbsent() {
		Map<String, Object> cache = obj("id", "c1", "path", "/volume1");
		Object st = storage(cache);
		T.check(SynologyTopology.cacheForVolume(MAPS, vol1(), st) == cache,
				"path fallback");
		T.eq("SSD Cache (Volume 1)", SynologyTopology.cacheName(MAPS, cache, st),
				"name by path fallback");
		Map<String, Object> blank = obj("id", "c2", "mountSpaceId", "",
				"path", "/volume2");
		T.check(SynologyTopology.cacheForVolume(MAPS, vol2(), storage(blank)) == blank,
				"a blank mountSpaceId counts as absent");
	}

	static void noJoinKeyNoJoin() {
		Map<String, Object> cache = obj("id", "c1");
		Object st = storage(cache);
		T.eq(null, SynologyTopology.cacheForVolume(MAPS, vol1(), st), "no key");
		T.eq("SSD Cache", SynologyTopology.cacheName(MAPS, cache, st), "plain name");
		// Build 31 matched a volume without an id to a cache without a
		// mountSpaceId (both read as ""); no longer.
		Map<String, Object> noId = obj("num_id", 5, "vol_path", "/volume5");
		T.check(!SynologyTopology.serves(MAPS, cache, noId), "empty never equals empty");
	}

	/** The edge goes exactly where the metrics go, path fallback included. */
	@SuppressWarnings("unchecked")
	static void metricsAndEdgeAgree() {
		SynologyTopology.Input<Object> in = fullFixture();
		Map<String, Object> st = (Map<String, Object>) ((Map<String, Object>) in.storage).get("data");
		Map<String, Object> cache = (Map<String, Object>) ((List<Object>) st.get("ssdCaches")).get(0);
		cache.remove("mountSpaceId");
		cache.put("path", "/volume1");
		List<String> edges = run(in, new ArrayList<>(), new ArrayList<>());
		SynologyTopology.Obj vol = o("SynologyVolume", "Volume 1", "volume_id", "/volume1");
		SynologyTopology.Obj c = o("SynologySsdCache", "SSD Cache (Volume 1)", "cache_id",
				"alloc_cache_1_1");
		T.check(edges.contains(edge(vol, c)), "path-joined cache gets its edge: " + edges);
		Object volNode = ((List<Object>) st.get("volumes")).get(0);
		T.check(SynologyTopology.cacheForVolume(MAPS, volNode, in.storage) == cache,
				"and the volume's metrics come from it");
	}
}
