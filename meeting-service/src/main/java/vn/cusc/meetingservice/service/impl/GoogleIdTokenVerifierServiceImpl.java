package vn.cusc.meetingservice.service.impl;

import com.google.api.client.googleapis.auth.oauth2.GoogleIdToken;
import com.google.api.client.googleapis.auth.oauth2.GoogleIdTokenVerifier;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.Collections;
import org.springframework.stereotype.Service;
import vn.cusc.meetingservice.config.GoogleAuthProperties;
import vn.cusc.meetingservice.exception.BusinessValidationException;
import vn.cusc.meetingservice.service.GoogleIdTokenVerifierService;

@Service
public class GoogleIdTokenVerifierServiceImpl implements GoogleIdTokenVerifierService {

    private final GoogleAuthProperties googleAuthProperties;

    public GoogleIdTokenVerifierServiceImpl(GoogleAuthProperties googleAuthProperties) {
        this.googleAuthProperties = googleAuthProperties;
    }

    @Override
    public VerifiedGoogleToken verify(String credential) {
        if (!googleAuthProperties.isEnabled()) {
            throw new BusinessValidationException("Google auth chua duoc bat");
        }
        try {
            GoogleIdTokenVerifier verifier = new GoogleIdTokenVerifier.Builder(new NetHttpTransport(), GsonFactory.getDefaultInstance())
                .setAudience(Collections.singletonList(googleAuthProperties.getClientId()))
                .build();
            GoogleIdToken idToken = verifier.verify(credential);
            if (idToken == null) {
                throw new BusinessValidationException("Google ID token khong hop le");
            }
            GoogleIdToken.Payload payload = idToken.getPayload();
            if (!Boolean.TRUE.equals(payload.getEmailVerified())) {
                throw new BusinessValidationException("Google email chua duoc verify");
            }
            String email = payload.getEmail();
            if (!googleAuthProperties.getAllowedDomainList().isEmpty()) {
                String domain = email.substring(email.indexOf('@') + 1).toLowerCase();
                if (!googleAuthProperties.getAllowedDomainList().contains(domain)) {
                    throw new BusinessValidationException("Google domain khong duoc phep");
                }
            }
            return new VerifiedGoogleToken(payload.getSubject(), email, (String) payload.get("name"), (String) payload.get("picture"),
                true, payload.getIssuer(), payload.getAudienceAsList().isEmpty() ? null : payload.getAudienceAsList().get(0));
        } catch (GeneralSecurityException | IOException exception) {
            throw new BusinessValidationException("Không thể xác minh đăng nhập Google. Vui lòng thử lại.");
        }
    }
}
