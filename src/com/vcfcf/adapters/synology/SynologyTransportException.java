package com.vcfcf.adapters.synology;

import java.io.IOException;

/**
 * Build 31 (issue #13): no DSM answer at all (connect, TLS or timeout
 * failure). DEF-001 forbids chaining the raw exception, whose message can
 * carry the request URL with {@code passwd=} or {@code _sid=}, so its class
 * is kept here instead: an unreachable NAS maps to DOWN and a connect
 * failure stops the snapshot build, while a TLS failure stays ERROR. The
 * message carries the endpoint label and a redacted reason only.
 */
final class SynologyTransportException extends IOException {

	private static final long serialVersionUID = 1L;

	private final SynologyFailures.Transport kind;

	SynologyTransportException(String message, SynologyFailures.Transport kind) {
		super(message);
		this.kind = kind;
	}

	/** How the original transport failure was classed. */
	SynologyFailures.Transport kind() {
		return kind;
	}
}
