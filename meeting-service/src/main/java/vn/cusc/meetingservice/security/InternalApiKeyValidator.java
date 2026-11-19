package vn.cusc.meetingservice.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

public final class InternalApiKeyValidator {
    private InternalApiKeyValidator() { }
    public static boolean matches(String expected, String supplied) {
        return expected != null && !expected.isBlank() && supplied != null && !supplied.isBlank()
            && supplied.length() <= 4096
            && MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), supplied.getBytes(StandardCharsets.UTF_8));
    }
}
