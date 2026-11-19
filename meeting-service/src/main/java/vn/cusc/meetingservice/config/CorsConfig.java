package vn.cusc.meetingservice.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class CorsConfig {

    @Bean
    public WebMvcConfigurer webMvcConfigurer(FrontendProperties frontendProperties) {
        return new WebMvcConfigurer() {
            @Override
            public void addCorsMappings(CorsRegistry registry) {
                // registry.addMapping("/api/**")
                //     .allowedOrigins(frontendProperties.getAllowedOrigins().toArray(String[]::new))
                //     .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                //     .allowedHeaders("*");

                registry.addMapping("/api/**")
                    .allowedOrigins(
                        frontendProperties.getAllowedOrigins().toArray(String[]::new)
                    )
                    .allowedOriginPatterns(
                        frontendProperties.getAllowedOriginPatterns().toArray(String[]::new)
                    )
                    .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                    .allowedHeaders("*");
            }
        };
    }
}
