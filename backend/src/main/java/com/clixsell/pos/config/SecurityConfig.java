package com.clixsell.pos.config;

import com.clixsell.pos.auth.AuthService;
import com.clixsell.pos.auth.TokenAuthenticationFilter;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.io.IOException;
import java.util.List;

/**
 * Who may call which API. Enforced by the backend for every request, whatever
 * the app shows or hides.
 *
 * <pre>
 * POST /api/auth/login                     anyone
 * /api/auth/**                             any logged-in user
 * /api/admin/**                            SYSTEM_ADMIN
 * /api/store/cashiers/**                   STORE_ADMIN
 * GET  /api/store                          STORE_ADMIN, CASHIER
 * GET  /api/products/generate-barcode      STORE_ADMIN
 * GET  /api/products/**                    STORE_ADMIN, CASHIER
 * POST/PUT/DELETE /api/products/**         STORE_ADMIN
 * /api/sales/**, /api/returns              STORE_ADMIN, CASHIER (POS)
 * </pre>
 * Not logged in → 401. Wrong role → 403. Which store's data a user sees is
 * decided separately, from the logged-in user (see CurrentUser).
 */
@Configuration
public class SecurityConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public SecurityFilterChain apiSecurity(HttpSecurity http, AuthService authService) throws Exception {
        http
                // A token in a header (not a cookie) is used, so CSRF protection is not needed.
                .csrf(csrf -> csrf.disable())
                .cors(Customizer.withDefaults())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .httpBasic(b -> b.disable())
                .formLogin(f -> f.disable())
                .logout(l -> l.disable())
                .exceptionHandling(e -> e
                        .authenticationEntryPoint((req, res, ex) ->
                                writeError(res, 401, "UNAUTHORIZED", "Please log in"))
                        .accessDeniedHandler((req, res, ex) ->
                                writeError(res, 403, "FORBIDDEN", "You are not allowed to do this")))
                .authorizeHttpRequests(a -> a
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .requestMatchers("/error").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/auth/login").permitAll()
                        .requestMatchers("/api/auth/**").authenticated()
                        .requestMatchers("/api/admin/**").hasRole("SYSTEM_ADMIN")
                        .requestMatchers("/api/store/cashiers", "/api/store/cashiers/**").hasRole("STORE_ADMIN")
                        .requestMatchers(HttpMethod.GET, "/api/store").hasAnyRole("STORE_ADMIN", "CASHIER")
                        .requestMatchers(HttpMethod.GET, "/api/products/generate-barcode").hasRole("STORE_ADMIN")
                        .requestMatchers(HttpMethod.GET, "/api/products", "/api/products/**").hasAnyRole("STORE_ADMIN", "CASHIER")
                        .requestMatchers("/api/products", "/api/products/**").hasRole("STORE_ADMIN")
                        .requestMatchers("/api/sales", "/api/sales/**", "/api/returns", "/api/returns/**")
                        .hasAnyRole("STORE_ADMIN", "CASHIER")
                        .anyRequest().authenticated())
                .addFilterBefore(new TokenAuthenticationFilter(authService), UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    /**
     * Allows the Flutter app running in Chrome (http://localhost:&lt;any port&gt;)
     * to call this API. Without this, the browser blocks the requests (CORS).
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration cors = new CorsConfiguration();
        cors.setAllowedOriginPatterns(List.of("http://localhost:[*]", "http://127.0.0.1:[*]"));
        cors.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        cors.setAllowedHeaders(List.of("*"));
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", cors);
        return source;
    }

    /**
     * Users log in through /api/auth/login, not Spring's built-in login. This
     * empty user service stops Spring Boot from creating its default user and
     * printing a generated password.
     */
    @Bean
    public UserDetailsService noBuiltInUsers() {
        return username -> {
            throw new UsernameNotFoundException("Use /api/auth/login");
        };
    }

    private static void writeError(HttpServletResponse res, int status, String code, String message) throws IOException {
        res.setStatus(status);
        res.setContentType("application/json");
        res.setCharacterEncoding("UTF-8");
        res.getWriter().write("{\"status\":" + status + ",\"code\":\"" + code + "\",\"message\":\""
                + message + "\",\"fieldErrors\":{}}");
    }
}
