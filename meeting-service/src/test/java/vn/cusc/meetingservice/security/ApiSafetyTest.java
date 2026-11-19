package vn.cusc.meetingservice.security;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import vn.cusc.meetingservice.config.*;
import vn.cusc.meetingservice.exception.ApiRateLimitException;
import vn.cusc.meetingservice.exception.BusinessValidationException;
import vn.cusc.meetingservice.util.ApiPageRequests;

class ApiSafetyTest {
    @Test void paginationRejectsOversizedAndUnsafeSortWithoutPassingItToJpa() {
        for (String sort : List.of("participants.email,asc", "title,desc,secret", "title,invalid", "", "createdByAccount.passwordHash,asc"))
            assertThrows(BusinessValidationException.class, () -> ApiPageRequests.meeting(0, 20, sort));
        assertThrows(BusinessValidationException.class, () -> ApiPageRequests.meeting(0, 101, "startTime,desc"));
        assertThrows(BusinessValidationException.class, () -> ApiPageRequests.meeting(-1, 20, "startTime,desc"));
        assertThrows(BusinessValidationException.class, () -> ApiPageRequests.meeting(Integer.MAX_VALUE, 100, "startTime,desc"));
        assertEquals(2, ApiPageRequests.meeting(0, 100, "startTime,desc").getSort().stream().count());
    }
    @Test void budgetsAreAtomicUnderConcurrentAdmissions() throws Exception {
        var limiter = new ApiRateLimitService(new ApiSafetyProperties()); var accepted = new AtomicInteger();
        ExecutorService executor = Executors.newFixedThreadPool(8);
        try {
            var work = java.util.stream.IntStream.range(0, 100).mapToObj(index -> executor.submit(() -> {
                try { limiter.require("same-actor", 30); accepted.incrementAndGet(); } catch (ApiRateLimitException expected) { }
            })).toList(); for (var future : work) future.get(5, TimeUnit.SECONDS);
        } finally { executor.shutdownNow(); }
        assertEquals(30, accepted.get());
    }
    @Test void keyMemoryIsBoundedAndExpiredWindowsRecoverCapacity() {
        var properties = new ApiSafetyProperties(); properties.setMaximumRateKeys(2);
        var time = new AtomicLong(); var limiter = new ApiRateLimitService(properties, time::get);
        limiter.require("a", 2); limiter.require("b", 2);
        assertThrows(ApiRateLimitException.class, () -> limiter.require("c", 2));
        time.set(60_000_000_001L); assertDoesNotThrow(() -> limiter.require("c", 2));
    }
    @Test void missingInternalCredentialsNeverMatchIncludingAnEmptyConfiguration() {
        assertFalse(InternalApiKeyValidator.matches(null, null)); assertFalse(InternalApiKeyValidator.matches("", ""));
        assertFalse(InternalApiKeyValidator.matches("safe-key", "wrong")); assertTrue(InternalApiKeyValidator.matches("safe-key", "safe-key"));
    }
    @Test void productionRejectsDevelopmentKeysAndDisabledAdmissionLimits() {
        var environment = new MockEnvironment(); environment.setActiveProfiles("prod");
        var ai = new AiServiceProperties(); ai.setInternalApiKey("dev-ai-key"); var live = new LiveKitProperties(); live.setApiSecret("abcdefghijklmnop-safe");
        var safety = new ProductionSafetyValidator(environment, ai, live);
        assertThrows(IllegalStateException.class, safety::afterSingletonsInstantiated);
        ai.setInternalApiKey("0123456789abcdef0123456789abcdef"); assertDoesNotThrow(safety::afterSingletonsInstantiated);
        environment.setProperty("api-safety.rate-limit-enabled", "false"); assertThrows(IllegalStateException.class, safety::afterSingletonsInstantiated);
    }
}
