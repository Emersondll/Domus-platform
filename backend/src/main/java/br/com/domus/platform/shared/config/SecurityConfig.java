package br.com.domus.platform.shared.config;

import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpMethod;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;

/**
 * API stateless protegida por JWT (OAuth2 Resource Server). CSRF fica desabilitado porque não há sessão
 * nem cookie de autenticação: o token vai no cabeçalho Authorization.
 */
@Configuration
public class SecurityConfig {

    @Bean
    SecurityFilterChain api(HttpSecurity http, Clock clock,
            @Value("${portaria.rate-limit.enabled:true}") boolean rateLimitEnabled,
            @Value("${portaria.rate-limit.user-per-minute:1200}") int userPerMinute) throws Exception {
        return http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // Actuator só existe na porta de gestão (8082), que não é publicada pelo proxy reverso.
                        .requestMatchers("/actuator/**").permitAll()
                        .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                        .requestMatchers("/dev/**").permitAll()
                        // Ingestão de eventos: autenticada pela chave do dispositivo, verificada no serviço.
                        .requestMatchers(HttpMethod.POST, "/api/v1/devices/*/events").permitAll()
                        // API do agente local: autenticada por X-Agent-Id/X-Agent-Key, verificada no serviço.
                        .requestMatchers("/api/v1/agent/**").permitAll()
                        // Chamada do servidor de mídia (MediaMTX): valida o token da sessão; restrita por IP no serviço.
                        .requestMatchers(HttpMethod.POST, "/internal/media/auth").permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth -> oauth.jwt(Customizer.withDefaults()))
                // limite por pessoa: só depois que o token foi validado (o IP, sozinho, pune quem divide a mesma rede)
                .addFilterAfter(new UserRateLimitFilter(clock, rateLimitEnabled, userPerMinute), BearerTokenAuthenticationFilter.class)
                .build();
    }
}
