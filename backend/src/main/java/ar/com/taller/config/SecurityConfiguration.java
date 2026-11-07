package ar.com.taller.config;

import java.time.Clock;
import org.springframework.http.HttpMethod;
import java.util.List;
import ar.com.taller.acceso.ApiErrors;
import ar.com.taller.acceso.CuentaRepository;
import ar.com.taller.acceso.SessionGuardFilter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextHolderFilter;
import org.springframework.security.web.csrf.CsrfAuthenticationStrategy;
import org.springframework.security.web.csrf.CsrfException;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;
import org.springframework.security.web.authentication.session.ChangeSessionIdAuthenticationStrategy;
import org.springframework.security.web.authentication.session.CompositeSessionAuthenticationStrategy;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;

@Configuration
public class SecurityConfiguration {
    @Bean PasswordEncoder passwordEncoder() {
        return new Argon2PasswordEncoder(16, 32, 1, 19456, 2);
    }

    @Bean Clock clock() { return Clock.systemUTC(); }

    @Bean @ConditionalOnWebApplication
    UserDetailsService userDetailsService(CuentaRepository accounts) {
        return email -> accounts.findForLogin(email)
                .orElseThrow(() -> new UsernameNotFoundException("Credenciales inválidas"));
    }

    @Bean @ConditionalOnWebApplication
    AuthenticationManager authenticationManager(UserDetailsService users, PasswordEncoder encoder) {
        var provider = new DaoAuthenticationProvider(users);
        provider.setPasswordEncoder(encoder);
        return new ProviderManager(provider);
    }

    @Bean @ConditionalOnWebApplication
    HttpSessionCsrfTokenRepository csrfTokenRepository() { return new HttpSessionCsrfTokenRepository(); }

    @Bean @ConditionalOnWebApplication
    HttpSessionSecurityContextRepository securityContextRepository() { return new HttpSessionSecurityContextRepository(); }

    @Bean @ConditionalOnWebApplication
    SessionAuthenticationStrategy sessionAuthenticationStrategy(HttpSessionCsrfTokenRepository csrf) {
        return new CompositeSessionAuthenticationStrategy(List.of(
                new ChangeSessionIdAuthenticationStrategy(), new CsrfAuthenticationStrategy(csrf)));
    }

    @Bean @ConditionalOnWebApplication
    SecurityFilterChain securityFilterChain(HttpSecurity http, CuentaRepository accounts, Clock clock,
            HttpSessionCsrfTokenRepository csrf, HttpSessionSecurityContextRepository contexts) throws Exception {
        return http
                .csrf(config -> config.csrfTokenRepository(csrf))
                .securityContext(config -> config.securityContextRepository(contexts))
                .requestCache(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/health", "/api/auth/csrf", "/api/auth/login").permitAll()
                        .requestMatchers("/api/auth/me", "/api/auth/logout").authenticated()
                        .requestMatchers(HttpMethod.POST,"/api/vehiculos","/api/ordenes").hasRole("PERSONAL")
                        .requestMatchers(HttpMethod.GET,"/api/catalogos/**").hasAnyRole("PERSONAL","ADMINISTRACION")
                        .requestMatchers(HttpMethod.GET,"/api/vehiculos","/api/vehiculos/*","/api/ordenes","/api/ordenes/*").hasAnyRole("ADMINISTRACION","PERSONAL","CLIENTE")
                        .requestMatchers("/api/administracion/**").hasRole("ADMINISTRACION")
                        .requestMatchers("/api/personal/**").hasRole("PERSONAL")
                        .requestMatchers("/api/cliente/**").hasRole("CLIENTE")
                        .anyRequest().denyAll())
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint((req, res, ex) -> ApiErrors.write(res, 401,
                                "AUTENTICACION_REQUERIDA", "Ingresá para continuar."))
                        .accessDeniedHandler((req, res, ex) -> {
                            if (ex instanceof CsrfException) {
                                ApiErrors.write(res, 403, "CSRF_INVALIDO", "La solicitud perdió vigencia. Intentá nuevamente.");
                            } else {
                                ApiErrors.write(res, 403, "ACCESO_DENEGADO", "No tenés permiso para esta acción.");
                            }
                        }))
                .addFilterAfter(new SessionGuardFilter(accounts, clock), SecurityContextHolderFilter.class)
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .build();
    }
}
