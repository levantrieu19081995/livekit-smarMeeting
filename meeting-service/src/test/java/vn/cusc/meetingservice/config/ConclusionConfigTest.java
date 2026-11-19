package vn.cusc.meetingservice.config;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.task.TaskSchedulingAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

class ConclusionConfigTest {
    @Test void longAiCallDoesNotReplaceOrBlockConfiguredCaptureSttScheduler() {
        new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(TaskSchedulingAutoConfiguration.class))
            .withUserConfiguration(ConclusionConfig.class)
            .withBean(AiServiceProperties.class, AiServiceProperties::new)
            .withPropertyValues("spring.task.scheduling.pool.size=5", "spring.task.scheduling.thread-name-prefix=existing-worker-")
            .run(context -> {
                assertNull(context.getStartupFailure());
                var regular = context.getBean("taskScheduler", ThreadPoolTaskScheduler.class);
                var ai = context.getBean("conclusionScheduler", ThreadPoolTaskScheduler.class);
                assertNotSame(regular, ai); assertEquals(5, regular.getScheduledThreadPoolExecutor().getCorePoolSize()); assertEquals(1, ai.getScheduledThreadPoolExecutor().getCorePoolSize());
                assertEquals("existing-worker-", regular.getThreadNamePrefix());
                var aiStarted = new CountDownLatch(1); var releaseAi = new CountDownLatch(1);
                var regularDone = new CountDownLatch(1);
                try {
                    ai.schedule(() -> {
                        aiStarted.countDown();
                        try { releaseAi.await(5, TimeUnit.SECONDS); }
                        catch (InterruptedException error) { Thread.currentThread().interrupt(); }
                    }, Instant.now());
                    assertTrue(aiStarted.await(3, TimeUnit.SECONDS));
                    regular.schedule(regularDone::countDown, Instant.now());
                    assertTrue(regularDone.await(3, TimeUnit.SECONDS), "Capture/STT scheduler must keep running while AI is busy");
                } finally { releaseAi.countDown(); }
            });
    }
}
