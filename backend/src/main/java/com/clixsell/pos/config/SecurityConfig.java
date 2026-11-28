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
 * PUT  /api/auth/me/password               SYSTEM_ADMIN, STORE_ADMIN (not CASHIER)
 * /api/auth/**                             any logged-in user
 * /api/admin/** (stores, admins, backups)  SYSTEM_ADMIN
 * /api/store/cashiers/**                   STORE_ADMIN
 * GET  /api/store                          STORE_ADMIN, CASHIER
 * PUT  /api/store/settings                 STORE_ADMIN (own store)
 * /api/product-files/** (import / export)   STORE_ADMIN
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
                        // Own password (Settings → Profile): admins only. Cashier passwords
                        // are set by their Store Admin (/api/store/cashiers/{id}/password).
                        .requestMatchers("/api/auth/me/password").hasAnyRole("SYSTEM_ADMIN", "STORE_ADMIN")
                        .requestMatchers("/api/auth/me/security-status").hasAnyRole("SYSTEM_ADMIN", "STORE_ADMIN")
                        .requestMatchers("/api/auth/**").authenticated()
                        .requestMatchers("/api/admin/**").hasRole("SYSTEM_ADMIN")
                        .requestMatchers("/api/store/cashiers", "/api/store/cashiers/**").hasRole("STORE_ADMIN")
                        .requestMatchers(HttpMethod.GET, "/api/store").hasAnyRole("STORE_ADMIN", "CASHIER")
                        // Store settings (tax, discount, address, phone): own store's Store Admin.
                        .requestMatchers("/api/store/settings").hasRole("STORE_ADMIN")
                        .requestMatchers("/api/product-files", "/api/product-files/**").hasRole("STORE_ADMIN")
                        .requestMatchers(HttpMethod.GET, "/api/products/generate-barcode").hasRole("STORE_ADMIN")
                        // Packages (carton / box): every store user reads them; the Store Admin manages them.
                        .requestMatchers(HttpMethod.GET, "/api/packages").hasAnyRole("STORE_ADMIN", "CASHIER")
                        .requestMatchers("/api/packages", "/api/packages/**").hasRole("STORE_ADMIN")
                        .requestMatchers(HttpMethod.GET, "/api/products", "/api/products/**").hasAnyRole("STORE_ADMIN", "CASHIER")
                        .requestMatchers("/api/products", "/api/products/**").hasRole("STORE_ADMIN")
                        // Reports and Sales Analytics are Manager (Store Admin) features.
                        .requestMatchers("/api/reports", "/api/reports/**").hasRole("STORE_ADMIN")
                        // Guardian Mode (Figure 3.1): the Store Admin of the store only. This rule
                        // must stay explicit: the last rule below lets any logged-in user through.
                        .requestMatchers("/api/guardian", "/api/guardian/**").hasRole("STORE_ADMIN")
                        // Supplier Management and purchasing (Purchase Orders phases): Store Admin only.
                        .requestMatchers("/api/suppliers", "/api/suppliers/**").hasRole("STORE_ADMIN")
                        .requestMatchers("/api/purchase-recommendations", "/api/purchase-recommendations/**",
                                "/api/purchase-candidates", "/api/purchase-candidates/**",
                                "/api/purchase-orders", "/api/purchase-orders/**").hasRole("STORE_ADMIN")
                        // POS favorites: every store user reads them; only the Store Admin changes them.
                        .requestMatchers(HttpMethod.GET, "/api/favorites").hasAnyRole("STORE_ADMIN", "CASHIER")
                        .requestMatchers("/api/favorites").hasRole("STORE_ADMIN")
                        // Cash drawers and shifts (V7): store users; admin-only actions are checked by ShiftService.
                        .requestMatchers("/api/drawers", "/api/drawers/**", "/api/shifts", "/api/shifts/**")
                        .hasAnyRole("STORE_ADMIN", "CASHIER")
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
        // The product export file name and count (the browser hides other response headers).
        cors.setExposedHeaders(List.of("Content-Disposition", "X-Product-Count"));
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
