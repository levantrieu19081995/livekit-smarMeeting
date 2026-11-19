package vn.cusc.meetingservice.service;

public interface GoogleIdTokenVerifierService {
    VerifiedGoogleToken verify(String credential);

    record VerifiedGoogleToken(
        String subject,
        String email,
        String fullName,
        String avatarUrl,
        boolean emailVerified,
        String issuer,
        String audience
    ) {
    }
}
