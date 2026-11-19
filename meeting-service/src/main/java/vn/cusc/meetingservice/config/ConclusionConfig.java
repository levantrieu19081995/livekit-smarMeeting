package vn.cusc.meetingservice.config;

import java.time.Duration;
import org.springframework.boot.web.client.ClientHttpRequestFactorySettings;
import org.springframework.boot.web.client.ClientHttpRequestFactories;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.task.ThreadPoolTaskSchedulerBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.web.client.RestClient;

@Configuration
public class ConclusionConfig {
    // A custom AI scheduler disables Boot's automatic scheduler. Preserve the
    // configured default scheduler used by capture/STT and their recovery jobs.
    @Bean(name = "taskScheduler")
    @ConditionalOnMissingBean(name = "taskScheduler")
    public ThreadPoolTaskScheduler taskScheduler(ThreadPoolTaskSchedulerBuilder builder) {
        return builder.build();
    }
    @Bean public RestClient conclusionRestClient(AiServiceProperties properties) {
        var settings = ClientHttpRequestFactorySettings.DEFAULTS.withConnectTimeout(Duration.ofSeconds(10))
            .withReadTimeout(Duration.ofSeconds(120));
        return RestClient.builder().baseUrl(properties.getBaseUrl())
            .requestFactory(ClientHttpRequestFactories.get(settings)).build();
    }
    @Bean public ThreadPoolTaskScheduler conclusionScheduler() {
        var scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1); scheduler.setThreadNamePrefix("conclusion-");
        scheduler.setWaitForTasksToCompleteOnShutdown(true); scheduler.setAwaitTerminationSeconds(125);
        return scheduler;
    }
}
