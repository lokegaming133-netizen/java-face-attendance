package local.attendance;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextHolderFilter;
import org.springframework.security.web.context.SecurityContextRepository;

@SpringBootApplication
public class Application {
    public static void main(String[] args) { SpringApplication.run(Application.class, args); }

    @Bean BCryptPasswordEncoder encoder() { return new BCryptPasswordEncoder(); }
    @Bean SecurityContextRepository sessionContextRepository() { return new HttpSessionSecurityContextRepository(); }

    @Bean FilterRegistrationBean<AccountStatusFilter> accountFilterRegistration(AccountStatusFilter filter) {
        FilterRegistrationBean<AccountStatusFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false); // only run inside the Spring Security chain
        return registration;
    }

    @Bean SecurityFilterChain security(HttpSecurity h, AccountStatusFilter accountFilter,
                                      SecurityContextRepository repository) throws Exception {
        return h
            .securityContext(c -> c.securityContextRepository(repository))
            .authorizeHttpRequests(a -> a
                .requestMatchers("/login", "/login/**", "/login.html", "/auth/**", "/auth-pages.css", "/favicon.ico", "/error").permitAll()
                .requestMatchers("/teacher-premium.js", "/teacher-premium.css", "/teacher-card-top-wave.svg", "/teacher-card-bottom-wave.svg").permitAll()
                .requestMatchers("/role-login.html").denyAll()
                .requestMatchers("/admin.html", "/roles.js", "/roles.css", "/api/admin/**").hasRole("ADMIN")
                .requestMatchers("/admin-exact-gallery.css", "/admin-exact-gallery.js", "/admin-gallery.css", "/admin-gallery-background.webp").hasRole("ADMIN")
                .requestMatchers("/portal.html", "/portal.js", "/portal.css", "/student-mosaic-v1.css", "/student-mosaic-v1.js", "/api/portal/**").hasRole("STUDENT")
                .requestMatchers("/api/me", "/api/csrf").authenticated()
                .requestMatchers("/api/school/**", "/api/**").hasAnyRole("ADMIN", "FACULTY")
                .requestMatchers("/", "/index.html", "/app.js", "/school.js", "/school.css", "/workspace-role.js")
                    .hasAnyRole("ADMIN", "FACULTY")
                .anyRequest().denyAll())
            .exceptionHandling(e -> e.authenticationEntryPoint(new LoginUrlAuthenticationEntryPoint("/login")))
            .formLogin(AbstractHttpConfigurer::disable) // no password-only session; /auth/start -> OTP -> session
            .httpBasic(AbstractHttpConfigurer::disable)
            .logout(l -> l.logoutSuccessUrl("/login?logout").invalidateHttpSession(true).deleteCookies("JSESSIONID"))
            .addFilterAfter(accountFilter, SecurityContextHolderFilter.class)
            .build();
    }
}
