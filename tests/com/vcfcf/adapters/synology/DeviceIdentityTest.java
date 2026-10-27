package com.vcfcf.adapters.synology;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Build 34 (issue #11): the DiskStation identity is the DSM serial; without
 * one it is the configured endpoint, announced once; a serial that goes
 * missing after it was read fails the cycle instead of moving the objects.
 * Run from the repo root (ci/run_java_tests.sh).
 */
public final class DeviceIdentityTest {

	public static void main(String[] args) throws Exception {
		serialIsTheIdentity();
		missingSerialFallsBackToTheEndpointOnce();
		serialThatGoesMissingFailsTheCycle();
		restartWithASerialKeyedDiskStationFailsTheCycle();
		serialThatAppearsReplacesTheFallback();
		changedSerialIsANewDiskStation();
		identifierKeysAndBelonging();
		System.out.println("DeviceIdentityTest: all assertions passed");
	}

	static void serialIsTheIdentity() throws IOException {
		List<String> warns = new ArrayList<>();
		DeviceIdentity d = new DeviceIdentity("NAS1.lab", 5001);
		DeviceIdentity.Id id = d.resolve("20B0RYRXRF3KF", warns::add);
		T.eq("20B0RYRXRF3KF", id.value, "serial");
		T.check(id.fromSerial, "from serial");
		T.eq("DS1520+ 20B0RYRXRF3KF", id.diskstationName("DS1520+"),
				"DiskStation name unchanged from build 33");
		T.eq("20B0RYRXRF3KF", id.diskstationName(""), "no model: serial alone");
		T.eq("Synology World (20B0RYRXRF3KF)", id.worldName(), "World name");
		T.eq(0, warns.size(), "no WARN");
		T.eq("20B0RYRXRF3KF", d.resolve("20B0RYRXRF3KF", warns::add).value, "stable");
		T.eq(0, warns.size(), "still no WARN");
	}

	static void missingSerialFallsBackToTheEndpointOnce() throws IOException {
		for (String none : new String[] {null, "", "   "}) {
			List<String> warns = new ArrayList<>();
			DeviceIdentity d = new DeviceIdentity("  NAS1.Lab ", 5001);
			DeviceIdentity.Id id = d.resolve(none, warns::add);
			T.eq("host:nas1.lab:5001", id.value, "fallback for <" + none + ">");
			T.eq("nas1.lab:5001", id.label, "label");
			T.check(!id.fromSerial, "not from serial");
			T.eq(1, warns.size(), "one WARN");
			T.check(warns.get(0).contains("host:nas1.lab:5001"), "WARN names it");
			T.eq("host:nas1.lab:5001", d.resolve(none, warns::add).value,
					"same value every cycle");
			T.eq(1, warns.size(), "the WARN is not repeated");
		}
	}

	static void serialThatGoesMissingFailsTheCycle() throws IOException {
		DeviceIdentity d = new DeviceIdentity("nas1", 5001);
		d.resolve("ABC", x -> {});
		try {
			d.resolve(null, x -> {});
			throw new AssertionError("a missing serial after one was read must fail");
		} catch (IOException expected) {
			T.check(expected.getMessage().contains("ABC"), "names the serial");
		}
		T.eq("ABC", d.resolve("ABC", x -> {}).value, "recovers on the next good read");
	}

	/**
	 * Review B1: a fresh DeviceIdentity (collector restart, pak upgrade,
	 * account edit) has no in-memory latch. The platform's DiskStation
	 * objects stand in for it: a real serial there fails the cycle; the
	 * fallback, build 33's "unknown" and no DiskStation at all do not.
	 */
	static void restartWithASerialKeyedDiskStationFailsTheCycle() throws IOException {
		List<String> warns = new ArrayList<>();
		DeviceIdentity fresh = new DeviceIdentity("nas1", 5001);
		try {
			fresh.resolve(null, () -> Arrays.asList("host:nas1:5001", "ABC"),
					warns::add);
			throw new AssertionError("a serial-keyed DiskStation on the platform "
					+ "must fail a serial-less cycle after a restart");
		} catch (IOException expected) {
			T.check(expected.getMessage().contains("ABC"), "names the serial");
			T.check(expected.getMessage().contains("host:nas1:5001"),
					"names the fallback it refused");
		}
		T.eq(0, warns.size(), "no fallback WARN: the fallback was not taken");
		T.eq("ABC", fresh.resolve("ABC", () -> {
			throw new AssertionError("existing objects are not read when DSM "
					+ "sends a serial");
		}, warns::add).value, "a serial is taken without reading the platform");

		for (List<String> allowed : Arrays.<List<String>>asList(
				Collections.<String>emptyList(),
				Arrays.asList("host:nas1:5001"),
				Arrays.asList("unknown"),
				Arrays.asList("", "  "))) {
			DeviceIdentity d = new DeviceIdentity("nas1", 5001);
			T.eq("host:nas1:5001", d.resolve(null, () -> allowed, x -> {}).value,
					"fallback allowed with existing " + allowed);
		}
		T.eq("host:nas1:5001", new DeviceIdentity("nas1", 5001)
				.resolve(null, () -> null, x -> {}).value, "no list: fallback");

		T.check(DeviceIdentity.isRealSerial("ABC"), "a serial is real");
		T.check(!DeviceIdentity.isRealSerial("host:nas1:5001"), "fallback is not");
		T.check(!DeviceIdentity.isRealSerial("unknown"), "build 33 placeholder is not");
		T.check(!DeviceIdentity.isRealSerial(null), "absent is not");
	}

	static void serialThatAppearsReplacesTheFallback() throws IOException {
		List<String> warns = new ArrayList<>();
		DeviceIdentity d = new DeviceIdentity("nas1", 5001);
		d.resolve(null, warns::add);
		T.eq("ABC", d.resolve("ABC", warns::add).value, "serial taken");
		T.eq(2, warns.size(), "fallback WARN, then the move WARN");
		T.check(warns.get(1).contains("ABC") && warns.get(1).contains("host:nas1:5001"),
				"the move WARN names both: " + warns.get(1));
		try {
			d.resolve(null, warns::add);
			throw new AssertionError("no going back to the fallback");
		} catch (IOException expected) {
			// as serialThatGoesMissingFailsTheCycle
		}
	}

	static void changedSerialIsANewDiskStation() throws IOException {
		List<String> warns = new ArrayList<>();
		DeviceIdentity d = new DeviceIdentity("nas1", 5001);
		d.resolve("ABC", warns::add);
		T.eq("XYZ", d.resolve("XYZ", warns::add).value, "new serial taken");
		T.eq(1, warns.size(), "one WARN");
		T.check(warns.get(0).contains("ABC") && warns.get(0).contains("XYZ"),
				"names both serials");
	}

	static void identifierKeysAndBelonging() {
		T.eq("serial", DeviceIdentity.identifierKeyFor("SynologyDiskstation"),
				"DiskStation key");
		T.eq("diskstation_id", DeviceIdentity.identifierKeyFor("SynologyVolume"),
				"child key");
		T.eq("diskstation_id", DeviceIdentity.identifierKeyFor("SynologyWorld"),
				"World key");
		DeviceIdentity.Id id = new DeviceIdentity.Id("ABC", "ABC", true);
		T.check(DeviceIdentity.belongs("ABC", id), "same identity belongs");
		T.check(!DeviceIdentity.belongs(null, id), "pre-build-34 object (no identifier)");
		T.check(!DeviceIdentity.belongs("unknown", id), "pre-build-34 'unknown' DiskStation");
		T.check(!DeviceIdentity.belongs("host:nas1:5001", id), "an earlier fallback");
		T.check(!DeviceIdentity.belongs("ABC", null), "no identity");
	}
}
