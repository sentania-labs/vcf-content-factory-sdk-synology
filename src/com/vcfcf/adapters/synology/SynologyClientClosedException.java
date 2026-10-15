package com.vcfcf.adapters.synology;

import java.io.IOException;

/**
 * Build 31 (review W5): a call on an API client whose configuration was
 * replaced (instance edited) or discarded. The client never logs in again,
 * so a cycle still running on it cannot open a session nobody logs out, or
 * send a refused login (an old password) that counts toward DSM Auto Block.
 * Ends the snapshot build ({@link SynologyFailures#endsBuild}); ERROR, not
 * DOWN.
 */
final class SynologyClientClosedException extends IOException {

	private static final long serialVersionUID = 1L;

	static final String MESSAGE = "Synology API client closed (the adapter "
			+ "instance was edited or removed); not logging in again";

	SynologyClientClosedException() {
		super(MESSAGE);
	}
}
