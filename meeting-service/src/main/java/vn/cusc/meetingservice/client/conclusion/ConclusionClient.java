package vn.cusc.meetingservice.client.conclusion;

import vn.cusc.meetingservice.dto.conclusion.ConclusionContracts.ProviderRequest;
import vn.cusc.meetingservice.dto.conclusion.ConclusionContracts.ProviderResponse;

public interface ConclusionClient {
    ProviderResponse generate(ProviderRequest request);
    class Failure extends RuntimeException {
        public final boolean retryable;
        public final String code;
        public Failure(boolean retryable, String code) {
            super(code); this.retryable = retryable; this.code = code;
        }
    }
}
