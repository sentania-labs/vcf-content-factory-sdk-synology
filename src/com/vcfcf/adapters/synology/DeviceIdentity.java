package com.vcfcf.adapters.synology;

import java.io.IOException;
import java.util.Collections;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Build 34 (issue #11): which DiskStation a configuration's objects belong
 * to. Before build 34 every object below the DiskStation was identified by
 * its DSM-local id alone ({@code reuse_1}, {@code /volume1}, {@code sata1},
 * the UPS model) and the World by a constant, ids that are the same on
 * almost every DiskStation, so two adapter instances (two NASes) wrote into
 * the same VCF Operations objects. From build 34 the DiskStation identity is
 * a second identifying identifier, {@link #ID_KEY}, on every object kind
 * below it and on the World (which is so one per DiskStation), and the
 * DiskStation's own {@code serial} identifier holds the same value.
 *
 * <p><b>The identity is the DSM serial number</b> ({@code SYNO.DSM.Info}
 * {@code serial}). When DSM sends no serial (a blank or missing field: a
 * non-administrator account or a Virtual DSM may not get one), the fallback
 * is the endpoint this instance is configured with, {@code host:<host>:<port>}
 * (host lower-cased and trimmed). It is deterministic: it comes from the
 * instance's own settings, not from anything DSM sends, so it changes only
 * when an administrator edits the host or port. Per configuration:
 * <ul>
 *   <li>the fallback is announced by one WARN, never used silently;</li>
 *   <li>once a serial has been read, a later answer without one fails the
 *       snapshot build (every object ERROR for the cycle) instead of moving
 *       the objects to the fallback identity and back;</li>
 *   <li>the same holds across a collector restart, a pak upgrade and an
 *       account edit, which forget the in-memory latch above: an answer
 *       without a serial also fails the build while this adapter instance
 *       already has a DiskStation object whose {@code serial} identifier is
 *       a real serial (not {@code host:}-prefixed, and not build 33's
 *       {@code unknown} placeholder). That state is the platform's, read
 *       through the caller's supplier, so it survives the restart. Deleting
 *       that DiskStation object accepts the fallback;</li>
 *   <li>a serial that appears while the fallback is in use is taken (the
 *       objects move to the serial identity once), with one WARN;</li>
 *   <li>a serial that changes (the host now answers as a different NAS) is
 *       taken, with one WARN: it is a different DiskStation.</li>
 * </ul>
 * No VCF Ops SDK and no framework-jar dependency, so the plain-main tests
 * drive it directly.
 */
final class DeviceIdentity {

	/** The identifying identifier every object kind below the DiskStation carries. */
	static final String ID_KEY = "diskstation_id";

	/** Prefix of the fallback identity. A DSM serial never starts with it. */
	static final String HOST_PREFIX = "host:";

	/**
	 * The serial build 33 and earlier gave a DiskStation when DSM sent none.
	 * Not a real serial: an object carrying it never blocks the fallback.
	 */
	static final String LEGACY_NO_SERIAL = "unknown";

	/** One resolved identity. Immutable. */
	static final class Id {
		/** The identifier value: the serial, or {@code host:<host>:<port>}. */
		final String value;
		/** The value without the {@code host:} prefix, for display names. */
		final String label;
		/** Whether {@link #value} is the DSM serial number. */
		final boolean fromSerial;

		Id(String value, String label, boolean fromSerial) {
			this.value = value;
			this.label = label;
			this.fromSerial = fromSerial;
		}

		/**
		 * The DiskStation's display name: {@code <model> <serial>} as before
		 * build 34, or {@code <model> <host>:<port>} on the fallback.
		 */
		String diskstationName(String model) {
			return model == null || model.isEmpty() ? label : model + " " + label;
		}

		/** The World's display name, one World per DiskStation. */
		String worldName() {
			return "Synology World (" + label + ")";
		}

		@Override
		public String toString() {
			return value;
		}
	}

	/** The DiskStation kind, the one kind whose identity is its own {@code serial}. */
	static final String DISKSTATION_KIND = "SynologyDiskstation";

	/**
	 * The identifier that holds the DiskStation identity on an object of
	 * {@code kind}: {@code serial} on the DiskStation, {@link #ID_KEY} on
	 * every other kind.
	 */
	static String identifierKeyFor(String kind) {
		return DISKSTATION_KIND.equals(kind) ? "serial" : ID_KEY;
	}

	/**
	 * Whether an object whose DiskStation identifier holds {@code carried}
	 * belongs to {@code current}. False for an object from before build 34
	 * (no {@link #ID_KEY}, or the old {@code unknown} serial) and for one
	 * created under an identity this configuration no longer has.
	 */
	static boolean belongs(String carried, Id current) {
		return current != null && current.value.equals(carried);
	}

	private final String endpoint;
	private String serialSeen;
	private boolean fallbackInUse;

	/**
	 * @param host the configured host (never blank in practice: the config
	 *             defaults it to {@code localhost})
	 * @param port the configured port
	 */
	DeviceIdentity(String host, int port) {
		String h = host == null ? "" : host.trim().toLowerCase(Locale.ROOT);
		this.endpoint = h.isEmpty() ? null : h + ":" + port;
	}

	/** Whether {@code serial} is a usable serial: present and not blank. */
	static boolean readable(String serial) {
		return serial != null && !serial.trim().isEmpty();
	}

	/**
	 * Whether a DiskStation object's {@code serial} identifier value is a
	 * DSM serial: readable, not the fallback ({@code host:...}) and not
	 * build 33's {@code unknown} placeholder.
	 */
	static boolean isRealSerial(String carried) {
		return readable(carried) && !carried.startsWith(HOST_PREFIX)
				&& !LEGACY_NO_SERIAL.equals(carried);
	}

	/** {@link #resolve(String, Supplier, Consumer)} with no existing objects. */
	Id resolve(String serial, Consumer<String> warn) throws IOException {
		return resolve(serial, Collections::emptyList, warn);
	}

	/**
	 * Resolves this cycle's identity from {@code SYNO.DSM.Info}'s
	 * {@code serial} field.
	 *
	 * @param serial   the field's value, {@code null} when absent
	 * @param existing the {@code serial} identifier values of the DiskStation
	 *                 objects this adapter instance already has on the
	 *                 platform; read only when DSM sent no serial and none
	 *                 was read earlier in this configuration
	 * @param warn     receives the one-off WARN lines described above
	 * @throws IOException when no serial is sent after one has been read in
	 *                     this configuration, or while this instance already
	 *                     has a DiskStation with a real serial, or when
	 *                     neither a serial nor a host is available
	 */
	synchronized Id resolve(String serial,
			Supplier<? extends Iterable<String>> existing,
			Consumer<String> warn) throws IOException {
		if (readable(serial)) {
			if (fallbackInUse) {
				warn.accept("DiskStation identity: DSM now reports serial "
						+ serial + "; this instance's objects move from the "
						+ "fallback identity " + HOST_PREFIX + endpoint
						+ " to the serial once. The objects with the "
						+ "fallback identity receive no more data; delete "
						+ "them (see the README upgrade section).");
				fallbackInUse = false;
			} else if (serialSeen != null && !serialSeen.equals(serial)) {
				warn.accept("DiskStation identity: the NAS at " + endpoint
						+ " now reports serial " + serial + " (was "
						+ serialSeen + "); it is treated as a different "
						+ "DiskStation, with new objects.");
			}
			serialSeen = serial;
			return new Id(serial, serial, true);
		}
		if (serialSeen != null) {
			throw new IOException("SYNO.DSM.Info returned no serial this cycle "
					+ "but returned " + serialSeen + " earlier; the cycle "
					+ "fails rather than moving this DiskStation's objects to "
					+ "the fallback identity " + HOST_PREFIX + endpoint);
		}
		// Review B1 (build 34): the latch above is in memory, so a restart,
		// a pak upgrade or an account edit forgets it. The platform does not:
		// a DiskStation this instance already has under a real serial means
		// DSM sent one before, so fail rather than re-key the whole tree.
		Iterable<String> carried = existing == null ? null : existing.get();
		if (carried != null) {
			for (String c : carried) {
				if (isRealSerial(c)) {
					throw new IOException("SYNO.DSM.Info returned no serial, "
							+ "and this adapter instance already has the "
							+ "DiskStation with serial " + c + "; the cycle "
							+ "fails rather than moving its objects to the "
							+ "fallback identity " + HOST_PREFIX + endpoint
							+ ". Give the account administrator rights again, "
							+ "or delete that DiskStation object to accept the "
							+ "fallback identity.");
				}
			}
		}
		if (endpoint == null) {
			throw new IOException("SYNO.DSM.Info returned no serial and no "
					+ "host is configured; the DiskStation has no identity");
		}
		if (!fallbackInUse) {
			warn.accept("DiskStation identity: SYNO.DSM.Info returned no "
					+ "serial number (a non-administrator account or a "
					+ "Virtual DSM may not get one), so this instance's "
					+ "objects are identified by the configured endpoint "
					+ HOST_PREFIX + endpoint + ". Editing the host or port "
					+ "re-creates them; an account that can read the serial "
					+ "moves them to the serial once.");
			fallbackInUse = true;
		}
		return new Id(HOST_PREFIX + endpoint, endpoint, false);
	}
}
