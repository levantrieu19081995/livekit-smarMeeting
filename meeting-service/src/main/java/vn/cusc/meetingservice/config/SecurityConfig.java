package vn.cusc.meetingservice.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.session.jdbc.config.annotation.web.http.EnableJdbcHttpSession;
import vn.cusc.meetingservice.security.RestAccessDeniedHandler;
import vn.cusc.meetingservice.security.RestAuthenticationEntryPoint;
import vn.cusc.meetingservice.service.UserAccountService;
import vn.cusc.meetingservice.repository.UserAccountRepository;
import vn.cusc.meetingservice.security.AccountRefreshFilter;
import vn.cusc.meetingservice.security.ApiRateLimitFilter;
import vn.cusc.meetingservice.security.ApiRateLimitService;

@Configuration
@EnableMethodSecurity
@EnableJdbcHttpSession
@EnableConfigurationProperties(ApiSafetyProperties.class)
public class SecurityConfig {

    @Bean
    public ApiRateLimitService apiRateLimitService(ApiSafetyProperties properties) {
        return new ApiRateLimitService(properties);
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
                                                   RestAuthenticationEntryPoint authenticationEntryPoint,
                                                   RestAccessDeniedHandler accessDeniedHandler,
                                                   ObjectProvider<UserAccountRepository> accounts,
                                                   ObjectMapper json,
                                                   ApiRateLimitService budgets,
                                                   ApiSafetyProperties safetyProperties) throws Exception {
        CookieCsrfTokenRepository csrfTokenRepository = CookieCsrfTokenRepository.withHttpOnlyFalse();
        csrfTokenRepository.setCookieName("XSRF-TOKEN");
        csrfTokenRepository.setHeaderName("X-XSRF-TOKEN");
        csrfTokenRepository.setCookiePath("/");

        http
            .csrf(csrf -> csrf
                .csrfTokenRepository(csrfTokenRepository)
                .ignoringRequestMatchers(
                    "/internal/api/minutes/stt/jobs/*/progress",
                    "/internal/api/minutes/stt/jobs/*/completion",
                    "/api/livekit/webhook"
                ))
            .sessionManagement(session -> session
                .sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED)
                .sessionFixation(sessionFixation -> sessionFixation.migrateSession()))
            .exceptionHandling(exception -> exception
                .authenticationEntryPoint(authenticationEntryPoint)
                .accessDeniedHandler(accessDeniedHandler))
            .requestCache(AbstractHttpConfigurer::disable)
            .formLogin(AbstractHttpConfigurer::disable)
            .httpBasic(AbstractHttpConfigurer::disable)
            .logout(logout -> logout
                .logoutUrl("/api/auth/logout")
                .invalidateHttpSession(true)
                .deleteCookies("SESSION", "JSESSIONID", "XSRF-TOKEN")
                .logoutSuccessHandler((request, response, authentication) -> response.setStatus(204)))
            .authorizeHttpRequests(authorize -> authorize
                .requestMatchers(HttpMethod.GET, "/api/auth/me", "/api/auth/providers", "/api/auth/csrf").permitAll()
                .requestMatchers(HttpMethod.POST, "/api/auth/register", "/api/auth/login", "/api/auth/google").permitAll()
                .requestMatchers("/api/minutes", "/api/minutes/**").authenticated()
                .requestMatchers(
                    "/api/meetings/*/minutes",
                    "/api/meetings/*/minutes/**",
                    "/api/meetings/*/documents",
                    "/api/meetings/*/documents/**"
                ).authenticated()
                .requestMatchers(HttpMethod.GET, "/api/meetings/*/join-info", "/api/meetings/*/chat/messages").permitAll()
                // Q&A validates session/connection credentials in its own meeting-scoped policy.
                .requestMatchers("/api/meetings/*/qa/**").permitAll()
                .requestMatchers("/api/meetings/*/speaking/**").permitAll()
                .requestMatchers("/api/meetings/*/polls", "/api/meetings/*/polls/**").permitAll()
                .requestMatchers(
                    HttpMethod.POST,
                    "/api/meetings/*/join",
                    "/api/meetings/*/chat/messages"
                ).permitAll()
                .requestMatchers(HttpMethod.POST, "/api/livekit/webhook").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/meetings/*/capture/**").authenticated()
                .requestMatchers(HttpMethod.GET, "/actuator/health", "/actuator/info", "/swagger-ui/**", "/v3/api-docs/**").permitAll()
                .requestMatchers("/internal/api/**").permitAll()
                .requestMatchers("/api/admin/meeting-tools/**").hasRole("ADMIN")
                .requestMatchers("/api/users/**").hasRole("ADMIN")
                .requestMatchers("/api/directory/**").hasAnyRole("ADMIN", "MEETING_MANAGER", "USER")
                .requestMatchers(HttpMethod.GET, "/api/meetings").authenticated()
                .requestMatchers(HttpMethod.GET, "/api/meetings/*").authenticated()
                .requestMatchers("/api/meetings/*/participants", "/api/meetings/*/participants/**").authenticated()
                .requestMatchers(HttpMethod.POST, "/api/meetings/*/end").authenticated()
                .requestMatchers(HttpMethod.POST, "/api/meetings").authenticated()
                .requestMatchers(HttpMethod.PUT, "/api/meetings/**").authenticated()
                .requestMatchers(HttpMethod.DELETE, "/api/meetings/**").authenticated()
                .anyRequest().authenticated());

        http.addFilterBefore(new AccountRefreshFilter(accounts::getIfAvailable, json), AuthorizationFilter.class);
        http.addFilterAfter(new ApiRateLimitFilter(budgets, safetyProperties, json), AccountRefreshFilter.class);
        return http.build();
    }

    @Bean
    public DaoAuthenticationProvider authenticationProvider(UserDetailsService userDetailsService, PasswordEncoder passwordEncoder) {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider();
        provider.setUserDetailsService(userDetailsService);
        provider.setPasswordEncoder(passwordEncoder);
        return provider;
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration configuration) throws Exception {
        return configuration.getAuthenticationManager();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
