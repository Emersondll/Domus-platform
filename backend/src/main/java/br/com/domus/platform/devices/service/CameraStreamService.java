package br.com.domus.platform.devices.service;

import br.com.domus.platform.devices.adapter.DeviceAdapter;
import br.com.domus.platform.devices.adapter.DeviceSecrets;
import br.com.domus.platform.devices.adapter.HostPolicy;
import br.com.domus.platform.devices.adapter.MediaServer;
import br.com.domus.platform.devices.entity.Device;
import br.com.domus.platform.devices.repository.CameraViewSessionStore;
import br.com.domus.platform.shared.ResidentProfileLookup;
import br.com.domus.platform.shared.ViewSessionRevoker;
import br.com.domus.platform.shared.audit.AuditOutcome;
import br.com.domus.platform.shared.audit.AuditService;
import br.com.domus.platform.shared.error.ConflictException;
import br.com.domus.platform.shared.error.NotFoundException;
import br.com.domus.platform.shared.tenant.AccessLevel;
import br.com.domus.platform.shared.tenant.AccessService;
import br.com.domus.platform.shared.tenant.Actor;
import br.com.domus.platform.shared.tenant.ResidentContext;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Vídeo ao vivo das câmeras. O navegador nunca recebe o endereço RTSP: recebe uma URL HLS do servidor de mídia com um
 * token curto (HMAC) preso ao caminho da câmera. O servidor de mídia valida o token chamando {@link #authorize}.
 * Abrir a visualização não aciona nenhum equipamento de acesso; é só leitura de imagem e fica auditada.
 */
@Service
public class CameraStreamService implements ViewSessionRevoker {

    /** Sessão de visualização: URL HLS já com o token e o instante em que ela deixa de valer. */
    public record Session(String hlsUrl, Instant expiresAt) {
    }

    /** Valor no lugar do identificador de sessão nos tokens internos de 60 s (o backend lendo o fluxo; não há pessoa). */
    private static final String INTERNAL_SESSION = "internal";

    private final DeviceService devices;
    private final CameraViewSessionStore sessions;
    private final DeviceCredentialService credentials;
    private final ResidentProfileLookup residents;
    private final int maxSessionsPerUser;
    private final MediaServer media;
    private final HostPolicy hostPolicy;
    private final DeviceSecrets secrets;
    private final AccessService access;
    private final AuditService audit;
    private final Clock clock;
    private final boolean enabled;
    private final byte[] tokenSecret;
    private final Duration tokenTtl;
    private final String publicHlsUrl;
    private final String publishSecret;
    private final Set<String> authAllowedIps;
    private final String internalRtspUrl;

    /** Cria o serviço de vídeo ao vivo com o servidor de mídia, o armazenamento de sessões e o teto por usuário. */
    public CameraStreamService(DeviceService devices, DeviceCredentialService credentials, CameraViewSessionStore sessions,
                               ResidentProfileLookup residents,
                               MediaServer media, HostPolicy hostPolicy, DeviceSecrets secrets,
                               AccessService access, AuditService audit, Clock clock,
                               @Value("${portaria.media.enabled:false}") boolean enabled,
                               @Value("${portaria.media.token-secret:}") String tokenSecret,
                               @Value("${portaria.media.token-ttl:PT10M}") Duration tokenTtl,
                               @Value("${portaria.media.public-hls-url:}") String publicHlsUrl,
                               @Value("${portaria.media.publish-secret:}") String publishSecret,
                               @Value("${portaria.media.auth-allowed-ips:127.0.0.1,::1,0:0:0:0:0:0:0:1}") String allowedIps,
                               @Value("${portaria.media.rtsp-internal-url:rtsp://127.0.0.1:8554}") String internalRtspUrl,
                               @Value("${portaria.media.max-sessions-per-user:12}") int maxSessionsPerUser) {
        if (enabled && tokenSecret.length() < 32) {
            throw new IllegalStateException("portaria.media.token-secret must have at least 32 characters");
        }
        this.devices = devices;
        this.sessions = sessions;
        this.credentials = credentials;
        this.residents = residents;
        this.maxSessionsPerUser = maxSessionsPerUser;
        this.media = media;
        this.hostPolicy = hostPolicy;
        this.secrets = secrets;
        this.access = access;
        this.audit = audit;
        this.clock = clock;
        this.enabled = enabled;
        this.tokenSecret = tokenSecret.getBytes(StandardCharsets.UTF_8);
        this.tokenTtl = tokenTtl;
        this.publicHlsUrl = publicHlsUrl.endsWith("/") ? publicHlsUrl.substring(0, publicHlsUrl.length() - 1) : publicHlsUrl;
        this.publishSecret = publishSecret;
        this.internalRtspUrl = internalRtspUrl.endsWith("/") ? internalRtspUrl.substring(0, internalRtspUrl.length() - 1) : internalRtspUrl;
        this.authAllowedIps = Arrays.stream(allowedIps.split(",")).map(String::trim).filter(s -> !s.isEmpty())
                .collect(Collectors.toSet());
    }

    /** Abre uma sessão de visualização. Exige nível OPERATE; a leitura de imagem é auditada na mesma transação. */
    @Transactional
    public Session open(UUID condominiumId, UUID deviceId) {
        Actor actor = access.require(condominiumId, AccessLevel.OPERATE, "camera.view");
        return start(actor.userId(), condominiumId, devices.find(condominiumId, deviceId), "camera.view", "STAFF");
    }

    /**
     * Câmeras que o síndico liberou para o morador, com vídeo disponível. Só morador do condomínio; outro papel = 404.
     * Dependente não vê nenhuma (B19: a decisão sobre menores, D17/D21, está em aberto; até lá, o mais restritivo).
     */
    @Transactional(readOnly = true)
    public List<Device> camerasForResident(UUID condominiumId) {
        ResidentContext me = access.requireResident(condominiumId, "camera.list.resident");
        if (!residents.isAdult(condominiumId, me.residentId())) {
            return List.of();
        }
        return devices.residentViewable(condominiumId).stream()
                .filter(d -> d.isActive() && d.hasVideoSource()).toList();
    }

    /** Sessão de visualização para o morador: só câmeras liberadas pelo síndico; cada abertura é auditada. */
    @Transactional
    public Session openForResident(UUID condominiumId, UUID deviceId) {
        ResidentContext me = access.requireResident(condominiumId, "camera.view.resident");
        Device device = devices.find(condominiumId, deviceId);
        if (!device.isResidentViewable() || !residents.isAdult(condominiumId, me.residentId())) {
            throw new NotFoundException("Camera not found");
        }
        return start(me.actor().userId(), condominiumId, device, "camera.view.resident", "RESIDENT");
    }

    private Session start(UUID userId, UUID condominiumId, Device device, String auditAction, String kind) {
        UUID deviceId = device.getId();
        String rtspUrl = device.getConfig().get("rtspUrl");
        if (!device.hasVideoSource()) {
            throw new ConflictException("Camera has no video source configured");
        }
        if (!device.isActive()) {
            throw new ConflictException("Camera is deactivated");
        }
        if (!enabled) {
            throw new ConflictException("Live video is not enabled on this installation");
        }
        String path = pathOf(device.getId());
        try {
            if (!device.isAgentPublished()) { // com agente local o caminho nasce quando ele publica; a central não puxa nada
                media.ensurePath(path, sourceUrl(device, rtspUrl));
            }
        } catch (DeviceAdapter.DeviceCommunicationException e) {
            throw new ConflictException("Media server unavailable");
        }
        Instant now = clock.instant();
        Instant expiresAt = now.plus(tokenTtl);
        UUID sessionId = UUID.randomUUID();
        // Abre espaço antes de gravar: o usuário fica com, no máximo, maxSessionsPerUser sessões ativas (a mais antiga cai).
        sessions.lockUser(userId);
        sessions.enforceLimit(userId, Math.max(0, maxSessionsPerUser - 1), now);
        sessions.create(sessionId, condominiumId, deviceId, userId, kind, now, expiresAt);
        audit.record(userId, null, condominiumId, auditAction, "device", deviceId.toString(),
                AuditOutcome.SUCCESS);
        return new Session(publicHlsUrl + "/" + path + "/index.m3u8?token=" + sign(path, sessionId.toString(), expiresAt),
                expiresAt);
    }

    /** A câmera tem fonte RTSP, está ativa e o servidor de mídia está ligado (o backend pode ler o fluxo dela). */
    public boolean canReadInternally(Device device) {
        return enabled && device.isActive() && device.hasVideoSource();
    }

    /**
     * URL RTSP <b>interna</b> do servidor de mídia para o próprio backend ler o fluxo (captura de quadro). Carrega um token
     * de 60 s do mesmo tipo da sessão de visualização; a senha da câmera continua só no servidor de mídia. Uso interno:
     * quem chama já verificou a permissão.
     */
    public String internalReadUrl(Device device) throws DeviceAdapter.DeviceCommunicationException {
        String path = pathOf(device.getId());
        if (!device.isAgentPublished()) {
            media.ensurePath(path, sourceUrl(device, device.getConfig().get("rtspUrl")));
        }
        return internalRtspUrl + "/" + path + "?token=" + sign(path, INTERNAL_SESSION, clock.instant().plusSeconds(60));
    }

    /**
     * Decisão do servidor de mídia para cada requisição. Leitura: token válido para o caminho. Publicação: só com o
     * segredo de publicação (fonte de teste ou agente local). Qualquer outra origem de chamada é negada.
     */
    public boolean authorize(String remoteIp, String action, String path, String query, String password) {
        if (!enabled || !authAllowedIps.contains(remoteIp) || path == null) {
            return false;
        }
        if ("publish".equals(action)) {
            return !publishSecret.isEmpty() && password != null && constantTimeEquals(publishSecret, password);
        }
        if (!"read".equals(action) && !"playback".equals(action)) {
            return false;
        }
        String token = queryParam(query, "token");
        return token != null && valid(path, token);
    }

    static String pathOf(UUID deviceId) {
        return "cam-" + deviceId;
    }

    /**
     * Fonte que o servidor de mídia lê. Credencial cadastrada pela tela (cifrada no banco) tem preferência sobre a variável de
     * ambiente; sem nenhuma das duas, a URL segue sem login. O resultado nunca vai ao navegador nem a log.
     */
    private String sourceUrl(Device device, String rtspUrl) {
        hostPolicy.requireAllowedRtsp(rtspUrl);
        String credential = credentials.userInfo(device.getId()).orElseGet(() -> {
            String envName = device.getConfig().get("rtspCredentialsEnv");
            String fromEnv = envName == null ? null : secrets.get(envName);
            return fromEnv == null || fromEnv.isBlank() ? null : fromEnv;
        });
        if (credential == null) {
            return rtspUrl;
        }
        int i = rtspUrl.indexOf("://") + 3;
        return rtspUrl.substring(0, i) + credential + "@" + rtspUrl.substring(i);
    }

    /** Token: {@code sessão.expiração.assinatura}. A assinatura cobre o caminho, a sessão e a expiração. */
    private String sign(String path, String sessionId, Instant expiresAt) {
        long exp = expiresAt.getEpochSecond();
        return sessionId + "." + exp + "." + mac(path + "|" + sessionId + "|" + exp);
    }

    private boolean valid(String path, String token) {
        String[] parts = token.split("\\.", -1);
        if (parts.length != 3) {
            return false;
        }
        long exp;
        try {
            exp = Long.parseLong(parts[1]);
        } catch (NumberFormatException e) {
            return false;
        }
        Instant now = clock.instant();
        if (exp <= now.getEpochSecond() || !constantTimeEquals(mac(path + "|" + parts[0] + "|" + exp), parts[2])) {
            return false;
        }
        if (INTERNAL_SESSION.equals(parts[0])) {
            return true;
        }
        try {
            return sessions.isActive(UUID.fromString(parts[0]), deviceIdOf(path), now);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static UUID deviceIdOf(String path) {
        if (!path.startsWith("cam-")) {
            throw new IllegalArgumentException("Not a camera path");
        }
        return UUID.fromString(path.substring(4));
    }

    /** {@inheritDoc} */
    @Override
    @Transactional
    public int revokeForUser(UUID userId, UUID condominiumId, String reason) {
        sessions.lockUser(userId);
        return sessions.revokeByUser(userId, condominiumId, reason, clock.instant());
    }

    private String mac(String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(tokenSecret, "HmacSHA256"));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private static boolean constantTimeEquals(String a, String b) {
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }

    private static String queryParam(String query, String name) {
        if (query == null) {
            return null;
        }
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0 && pair.substring(0, eq).equals(name)) {
                return URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
            }
        }
        return null;
    }
}
