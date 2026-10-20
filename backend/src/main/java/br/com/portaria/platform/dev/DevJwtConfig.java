package br.com.portaria.platform.dev;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

/**
 * Chaves RSA geradas em memória a cada subida, somente no perfil {@code dev}. Substitui o provedor OIDC
 * (Keycloak) para permitir validação local sem infraestrutura de identidade.
 */
@Configuration
@Profile("dev")
class DevJwtConfig {

    @Bean
    RSAKey devRsaKey() throws Exception {
        return new RSAKeyGenerator(2048).keyID("dev-key").generate();
    }

    @Bean
    JwtEncoder devJwtEncoder(RSAKey key) {
        return new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(key)));
    }

    @Bean
    JwtDecoder devJwtDecoder(RSAKey key) throws Exception {
        return NimbusJwtDecoder.withPublicKey(key.toRSAPublicKey()).build();
    }
}
