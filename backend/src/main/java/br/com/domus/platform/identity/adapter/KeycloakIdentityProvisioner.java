package br.com.domus.platform.identity.adapter;

import br.com.domus.platform.identity.service.IdentityProvisioner;
import br.com.domus.platform.shared.error.ConflictException;
import br.com.domus.platform.shared.error.ServiceUnavailableException;
import java.net.http.HttpClient;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * Cria contas pela API de administração do Keycloak usando uma conta de serviço (client credentials) com o mínimo de
 * permissões: gerenciar usuários do realm. O segredo vem de variável de ambiente e nunca é registrado em log.
 */
@Component
@ConditionalOnProperty(name = "portaria.identity.provisioner.enabled", havingValue = "true")
public class KeycloakIdentityProvisioner implements IdentityProvisioner {

    private static final Logger log = LoggerFactory.getLogger(KeycloakIdentityProvisioner.class);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final RestClient http;
    private final String realm;
    private final String clientId;
    private final String clientSecret;
    private String token;
    private Instant tokenExpiry = Instant.EPOCH;

    /** Construtor; as propriedades vêm de {@code portaria.identity.provisioner.*}. */
    public KeycloakIdentityProvisioner(@Value("${portaria.identity.provisioner.base-url}") String baseUrl,
                                       @Value("${portaria.identity.provisioner.realm:portaria}") String realm,
                                       @Value("${portaria.identity.provisioner.client-id:portaria-provisioner}") String clientId,
                                       @Value("${portaria.identity.provisioner.client-secret}") String clientSecret) {
        var factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build());
        factory.setReadTimeout(Duration.ofSeconds(8));
        this.http = RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
        this.realm = realm;
        this.clientId = clientId;
        this.clientSecret = clientSecret;
    }

    @Override
    public Created create(String email, String displayName, String providerRole) {
        String password = generatePassword();
        try {
            String bearer = bearer();
            // O perfil do Keycloak exige nome e sobrenome; sem sobrenome informado, a própria pessoa completa no primeiro acesso.
            String[] names = displayName.trim().split("\\s+", 2);
            boolean hasLastName = names.length == 2;
            Map<String, Object> body = Map.of("username", email, "email", email, "firstName", names[0],
                    "lastName", hasLastName ? names[1] : "", "enabled", true, "emailVerified", false,
                    "requiredActions", hasLastName ? List.of("UPDATE_PASSWORD") : List.of("UPDATE_PASSWORD", "UPDATE_PROFILE"),
                    "credentials", List.of(Map.of("type", "password", "value", password, "temporary", true)));
            String subject = http.post().uri("/admin/realms/{r}/users", realm).header("Authorization", "Bearer " + bearer)
                    .contentType(MediaType.APPLICATION_JSON).body(body).exchange((req, res) -> {
                        if (res.getStatusCode().value() == 409) {
                            throw new ConflictException("An account with this e-mail already exists in the identity provider");
                        }
                        if (!res.getStatusCode().is2xxSuccessful()) {
                            throw failed("create user", res);
                        }
                        String location = res.getHeaders().getFirst("Location");
                        if (location == null || !location.contains("/")) {
                            throw new ServiceUnavailableException("Identity provider did not return the new account");
                        }
                        return location.substring(location.lastIndexOf('/') + 1);
                    });
            try {
                assignRole(bearer, subject, providerRole);
            } catch (RuntimeException e) {
                delete(subject);
                throw e;
            }
            return new Created(subject, password);
        } catch (RestClientResponseException e) {
            // 401/403 aqui quase sempre é o segredo ou as permissões da conta de serviço; o detalhe vai só para o log
            log.warn("Identity provider answered {} while creating an account", e.getStatusCode().value());
            throw new ServiceUnavailableException("Identity provider rejected the provisioning request");
        } catch (RestClientException e) {
            log.warn("Identity provider unreachable while creating an account: {}", e.getClass().getSimpleName());
            throw new ServiceUnavailableException("Identity provider is not reachable");
        }
    }

    @Override
    public void delete(String subject) {
        try {
            http.delete().uri("/admin/realms/{r}/users/{id}", realm, subject).header("Authorization", "Bearer " + bearer())
                    .retrieve().toBodilessEntity();
        } catch (RuntimeException e) {
            log.error("Could not undo the creation of identity account {}; remove it manually in Keycloak", subject);
        }
    }

    @Override
    public String resetPassword(String subject) {
        String password = generatePassword();
        try {
            String bearer = bearer();
            http.put().uri("/admin/realms/{r}/users/{id}/reset-password", realm, subject)
                    .header("Authorization", "Bearer " + bearer).contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("type", "password", "value", password, "temporary", true)).retrieve().toBodilessEntity();
            // a senha antiga pode estar nas mãos de quem não deveria: derruba as sessões abertas
            http.post().uri("/admin/realms/{r}/users/{id}/logout", realm, subject)
                    .header("Authorization", "Bearer " + bearer).retrieve().toBodilessEntity();
            return password;
        } catch (RestClientResponseException e) {
            log.warn("Identity provider answered {} while resetting a password", e.getStatusCode().value());
            throw new ServiceUnavailableException("Identity provider rejected the password reset");
        } catch (RestClientException e) {
            log.warn("Identity provider unreachable while resetting a password: {}", e.getClass().getSimpleName());
            throw new ServiceUnavailableException("Identity provider is not reachable");
        }
    }

    /**
     * Dá o papel de realm à conta. Os papéis disponíveis são lidos pela rota do próprio usuário (basta gerenciar usuários);
     * ler {@code /roles/{nome}} exigiria também a permissão de ver a configuração do realm.
     */
    private void assignRole(String bearer, String subject, String providerRole) {
        List<?> available = http.get().uri("/admin/realms/{r}/users/{id}/role-mappings/realm/available", realm, subject)
                .header("Authorization", "Bearer " + bearer).retrieve().body(List.class);
        Object role = available == null ? null : available.stream()
                .filter(r -> r instanceof Map<?, ?> m && providerRole.equals(m.get("name"))).findFirst().orElse(null);
        if (role == null) {
            log.warn("Realm role '{}' not found in the identity provider; create it before provisioning accounts", providerRole);
            throw new ServiceUnavailableException("Identity provider has no role '" + providerRole + "'");
        }
        http.post().uri("/admin/realms/{r}/users/{id}/role-mappings/realm", realm, subject)
                .header("Authorization", "Bearer " + bearer).contentType(MediaType.APPLICATION_JSON).body(List.of(role))
                .retrieve().toBodilessEntity();
    }

    private synchronized String bearer() {
        if (token != null && Instant.now().isBefore(tokenExpiry)) {
            return token;
        }
        var form = new LinkedMultiValueMap<String, String>();
        form.add("grant_type", "client_credentials");
        form.add("client_id", clientId);
        form.add("client_secret", clientSecret);
        Map<?, ?> r = http.post().uri("/realms/{r}/protocol/openid-connect/token", realm)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED).body(form).retrieve().body(Map.class);
        if (r == null || !(r.get("access_token") instanceof String t)) {
            throw new ServiceUnavailableException("Identity provider refused the provisioning credentials");
        }
        long seconds = r.get("expires_in") instanceof Number n ? n.longValue() : 60;
        token = t;
        tokenExpiry = Instant.now().plusSeconds(Math.max(5, seconds - 15));
        return t;
    }

    private static ServiceUnavailableException failed(String what, ClientHttpResponse res) {
        try {
            log.warn("Identity provider rejected '{}' with status {}", what, res.getStatusCode().value());
        } catch (java.io.IOException ignored) {
            // só o log; o erro devolvido ao cliente não depende disso
        }
        return new ServiceUnavailableException("Identity provider rejected the request");
    }

    /** Senha provisória de 20 caracteres com maiúscula, minúscula, dígito e símbolo (a política do realm exige 12+). */
    static String generatePassword() {
        String upper = "ABCDEFGHJKLMNPQRSTUVWXYZ", lower = "abcdefghijkmnpqrstuvwxyz", digits = "23456789", sym = "-_.!#";
        String all = upper + lower + digits + sym;
        StringBuilder sb = new StringBuilder();
        sb.append(upper.charAt(RANDOM.nextInt(upper.length()))).append(lower.charAt(RANDOM.nextInt(lower.length())))
                .append(digits.charAt(RANDOM.nextInt(digits.length()))).append(sym.charAt(RANDOM.nextInt(sym.length())));
        while (sb.length() < 20) {
            sb.append(all.charAt(RANDOM.nextInt(all.length())));
        }
        return sb.toString();
    }
}
