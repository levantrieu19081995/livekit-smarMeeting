package vn.cusc.meetingservice.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI openAPI() {
        return new OpenAPI()
            .info(new Info()
                .title("CUSC Smart Meeting API")
                .version("v1")
                .description("Backend APIs for meetings, LiveKit orchestration, documents, minutes and AI callbacks."));
    }
}
