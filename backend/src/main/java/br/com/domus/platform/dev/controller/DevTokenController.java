package br.com.domus.platform.dev.controller;

import java.time.Instant;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Emite tokens de teste para os usuários semeados. Existe apenas no perfil {@code dev}. */
@RestController
@Profile("dev")
class DevTokenController {

    private final JwtEncoder encoder;

    DevTokenController(JwtEncoder encoder) {
        this.encoder = encoder;
    }

    @GetMapping("/dev/token")
    Map<String, String> token(@RequestParam String subject) {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer("portaria-dev").subject(subject)
                .issuedAt(now).expiresAt(now.plusSeconds(3600)).build();
        String value = encoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(SignatureAlgorithm.RS256).keyId("dev-key").build(), claims)).getTokenValue();
        return Map.of("access_token", value, "token_type", "Bearer");
    }
}
