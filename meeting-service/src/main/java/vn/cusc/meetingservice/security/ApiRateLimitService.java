package vn.cusc.meetingservice.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.function.LongSupplier;
import vn.cusc.meetingservice.config.ApiSafetyProperties;
import vn.cusc.meetingservice.exception.ApiRateLimitException;

/** Bounded per-process admission budgets; gateway budgets remain necessary for multiple replicas. */
public final class ApiRateLimitService {
    private record Window(long openedAt, int count) { }
    private final ApiSafetyProperties properties;
    private final LongSupplier clock;
    private final Map<String, Window> windows = new HashMap<>();

    public ApiRateLimitService(ApiSafetyProperties properties) { this(properties, System::nanoTime); }
    public ApiRateLimitService(ApiSafetyProperties properties, LongSupplier clock) {
        this.properties = properties; this.clock = clock;
    }

    public void loginIdentity(String identity) {
        require("login-id:" + hash(identity == null ? "" : identity.trim().toLowerCase(Locale.ROOT)),
            properties.getLoginPerIdentityPerMinute());
    }

    public synchronized void require(String key, int limit) {
        if (!properties.isRateLimitEnabled()) return;
        long now = clock.getAsLong();
        long windowNanos = 60_000_000_000L;
        Window value = windows.get(key);
        if (value == null || now - value.openedAt() >= windowNanos) {
            if (value == null && windows.size() >= Math.max(1, properties.getMaximumRateKeys())) {
                windows.entrySet().removeIf(entry -> now - entry.getValue().openedAt() >= windowNanos);
                if (windows.size() >= Math.max(1, properties.getMaximumRateKeys())) throw new ApiRateLimitException();
            }
            value = new Window(now, 0);
        }
        if (value.count() >= Math.max(1, limit)) throw new ApiRateLimitException();
        windows.put(key, new Window(value.openedAt(), value.count() + 1));
    }

    public static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }
}
