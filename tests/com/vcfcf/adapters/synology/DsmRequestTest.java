package com.vcfcf.adapters.synology;

/**
 * Build 35 (issue #16): the login and every authenticated call are form
 * POSTs whose path carries no parameter; account, password and session id
 * are in the body only. Run from the repo root (ci/run_java_tests.sh).
 */
public final class DsmRequestTest {

	public static void main(String[] args) {
		loginSecretsAreInTheBodyOnly();
		sidIsInTheBodyOnly();
		noSidBeforeLogin();
		cgiPathFromApiInfo();
		infoIsAGetWithoutBody();
		encodesUtf8AndReserved();
		redactMasksTheBody();
		System.out.println("DsmRequestTest: all assertions passed");
	}

	static final DsmApis.Pick ENTRY = new DsmApis.Pick("entry.cgi", 7);

	static void loginSecretsAreInTheBodyOnly() {
		DsmRequest r = DsmRequest.post(null, ENTRY, "SYNO.API.Auth", "login",
				"account=" + DsmRequest.urlEncode("svc-ops"),
				"passwd=" + DsmRequest.urlEncode("S3cr3t!&x=y"),
				"session=" + DsmRequest.urlEncode("VCFContentFactory-42"),
				"format=sid");
		T.eq("/webapi/entry.cgi", r.path, "login path carries no parameter");
		T.eq("api=SYNO.API.Auth&version=7&method=login&account=svc-ops"
				+ "&passwd=S3cr3t%21%26x%3Dy&session=VCFContentFactory-42&format=sid",
				r.body, "login body");
		T.check(!r.path.contains("?"), "no query string on login");
	}

	static void sidIsInTheBodyOnly() {
		DsmRequest r = DsmRequest.post("abc+SID/123", new DsmApis.Pick("entry.cgi", 2),
				"SYNO.DSM.Info", "getinfo");
		T.eq("/webapi/entry.cgi", r.path, "data path carries no parameter");
		T.eq("api=SYNO.DSM.Info&version=2&method=getinfo&_sid=abc%2BSID%2F123",
				r.body, "sid form-encoded in the body");
		DsmRequest share = DsmRequest.post("s", new DsmApis.Pick("entry.cgi", 1),
				"SYNO.Core.Share", "list", "shareType=all", "additional=%5B%22hidden%22%5D");
		T.eq("api=SYNO.Core.Share&version=1&method=list&_sid=s&shareType=all"
				+ "&additional=%5B%22hidden%22%5D", share.body, "extra params kept as given");
	}

	static void noSidBeforeLogin() {
		DsmRequest r = DsmRequest.post(null, ENTRY, "SYNO.API.Auth", "login");
		T.check(!r.body.contains("_sid"), "no _sid without a session: " + r.body);
	}

	static void cgiPathFromApiInfo() {
		DsmRequest r = DsmRequest.post("s", new DsmApis.Pick("auth.cgi", 6),
				"SYNO.API.Auth", "logout", "session=x");
		T.eq("/webapi/auth.cgi", r.path, "the CGI SYNO.API.Info names");
		T.check(r.body.startsWith("api=SYNO.API.Auth&version=6&method=logout&_sid=s"),
				"lowered version in the body: " + r.body);
	}

	static void infoIsAGetWithoutBody() {
		DsmRequest r = DsmRequest.get(DsmApis.INFO_PATH);
		T.eq(null, r.body, "SYNO.API.Info is a GET");
		T.check(!r.path.contains("_sid") && !r.path.contains("passwd"),
				"SYNO.API.Info carries no secret");
	}

	static void encodesUtf8AndReserved() {
		T.eq("a-b_c.d~e", DsmRequest.urlEncode("a-b_c.d~e"), "unreserved kept");
		T.eq("%20%2B%26%3D%25", DsmRequest.urlEncode(" +&=%"), "reserved encoded");
		T.eq("%C3%A9", DsmRequest.urlEncode("é"), "two-byte UTF-8");
		T.eq("%F0%9F%94%91", DsmRequest.urlEncode("🔑"),
				"a surrogate pair is one four-byte character");
		T.eq("", DsmRequest.urlEncode(null), "null is empty");
	}

	static void redactMasksTheBody() {
		DsmRequest login = DsmRequest.post(null, ENTRY, "SYNO.API.Auth", "login",
				"account=svc%2Dops", "passwd=S3cr3t%21", "format=sid");
		DsmRequest data = DsmRequest.post("abcSID123", ENTRY, "SYNO.DSM.Info", "getinfo");
		String r = SynologyRedaction.redact(login.body + " " + data.body);
		RedactionTest.noSecret(r, "body");
		T.check(r.contains("api=SYNO.DSM.Info"), "endpoint kept: " + r);
		String c = SynologyRedaction.redact("Cookie: smid=1; id=abcSID123; other=2");
		RedactionTest.noSecret(c, "cookie");
		T.eq("Cookie: smid=1; id=<redacted>; other=2", c, "only the id cookie masked");
		T.check(SynologyRedaction.redact("Cookie: device_id=dev1").contains("dev1"),
				"a field merely ending in id= is not masked");
	}
}
