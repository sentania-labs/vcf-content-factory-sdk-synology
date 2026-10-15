package com.vcfcf.adapters.synology;

import java.io.IOException;

/**
 * Build 31 (issue #12): DSM answered, but with {@code "success": false}. DSM
 * reports its errors as a JSON error code in the body of an HTTP 200
 * response, never as an HTTP status, so the code is kept as a field and the
 * snapshot cache can tell an authentication-class login refusal from any
 * other failure without parsing message text. The same code number means
 * different things for different APIs (400 on {@code SYNO.API.Auth} is a
 * wrong password, on another API it is that API's own error), so
 * {@link #isLogin()} records whether the refused call was the login. The
 * message carries the endpoint label and the code only, never a URL, a
 * password or a session id.
 */
final class SynologyDsmException extends IOException {

	private static final long serialVersionUID = 1L;

	private final int code;
	private final boolean login;

	SynologyDsmException(String message, int code, boolean login) {
		super(message);
		this.code = code;
		this.login = login;
	}

	/** The DSM error code from the response body (0 when absent). */
	int code() {
		return code;
	}

	/** True when the refused call was the {@code SYNO.API.Auth} login. */
	boolean isLogin() {
		return login;
	}
}
