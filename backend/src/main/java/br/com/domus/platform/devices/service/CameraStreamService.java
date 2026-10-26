package br.com.domus.platform.devices.service;

import br.com.domus.platform.devices.adapter.DeviceAdapter;
import br.com.domus.platform.devices.adapter.DeviceSecrets;
import br.com.domus.platform.devices.adapter.HostPolicy;
import br.com.domus.platform.devices.adapter.MediaServer;
import br.com.domus.platform.devices.entity.Device;
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
public class CameraStreamService {

    /** Sessão de visualização: URL HLS já com o token e o instante em que ela deixa de valer. */
    public record Session(String hlsUrl, Instant expiresAt) {
    }

    private final DeviceService devices;
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

    public CameraStreamService(DeviceService devices, MediaServer media, HostPolicy hostPolicy, DeviceSecrets secrets,
                               AccessService access, AuditService audit, Clock clock,
                               @Value("${portaria.media.enabled:false}") boolean enabled,
                               @Value("${portaria.media.token-secret:}") String tokenSecret,
                               @Value("${portaria.media.token-ttl:PT10M}") Duration tokenTtl,
                               @Value("${portaria.media.public-hls-url:}") String publicHlsUrl,
                               @Value("${portaria.media.publish-secret:}") String publishSecret,
                               @Value("${portaria.media.auth-allowed-ips:127.0.0.1,::1,0:0:0:0:0:0:0:1}") String allowedIps,
                               @Value("${portaria.media.rtsp-internal-url:rtsp://127.0.0.1:8554}") String internalRtspUrl) {
        if (enabled && tokenSecret.length() < 32) {
            throw new IllegalStateException("portaria.media.token-secret must have at least 32 characters");
        }
        this.devices = devices;
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
        return start(actor.userId(), condominiumId, devices.find(condominiumId, deviceId), "camera.view");
    }

    /** Câmeras que o síndico liberou para o morador, com vídeo disponível. Só morador do condomínio; outro papel = 404. */
    @Transactional(readOnly = true)
    public List<Device> camerasForResident(UUID condominiumId) {
        access.requireResident(condominiumId, "camera.list.resident");
        return devices.residentViewable(condominiumId).stream()
                .filter(d -> d.isActive() && d.getConfig().containsKey("rtspUrl")).toList();
    }

    /** Sessão de visualização para o morador: só câmeras liberadas pelo síndico; cada abertura é auditada. */
    @Transactional
    public Session openForResident(UUID condominiumId, UUID deviceId) {
        ResidentContext me = access.requireResident(condominiumId, "camera.view.resident");
        Device device = devices.find(condominiumId, deviceId);
        if (!device.isResidentViewable()) {
            throw new NotFoundException("Camera not found");
        }
        return start(me.actor().userId(), condominiumId, device, "camera.view.resident");
    }

    private Session start(UUID userId, UUID condominiumId, Device device, String auditAction) {
        UUID deviceId = device.getId();
        String rtspUrl = device.getConfig().get("rtspUrl");
        if (!"CAMERA".equals(device.getKind()) || rtspUrl == null) {
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
            media.ensurePath(path, sourceUrl(device, rtspUrl));
        } catch (DeviceAdapter.DeviceCommunicationException e) {
            throw new ConflictException("Media server unavailable");
        }
        Instant expiresAt = clock.instant().plus(tokenTtl);
        audit.record(userId, null, condominiumId, auditAction, "device", deviceId.toString(),
                AuditOutcome.SUCCESS);
        return new Session(publicHlsUrl + "/" + path + "/index.m3u8?token=" + sign(path, expiresAt), expiresAt);
    }

    /** A câmera tem fonte RTSP, está ativa e o servidor de mídia está ligado (o backend pode ler o fluxo dela). */
    public boolean canReadInternally(Device device) {
        return enabled && device.isActive() && "CAMERA".equals(device.getKind()) && device.getConfig().containsKey("rtspUrl");
    }

    /**
     * URL RTSP <b>interna</b> do servidor de mídia para o próprio backend ler o fluxo (captura de quadro). Carrega um token
     * de 60 s do mesmo tipo da sessão de visualização; a senha da câmera continua só no servidor de mídia. Uso interno:
     * quem chama já verificou a permissão.
     */
    public String internalReadUrl(Device device) throws DeviceAdapter.DeviceCommunicationException {
        String path = pathOf(device.getId());
        media.ensurePath(path, sourceUrl(device, device.getConfig().get("rtspUrl")));
        return internalRtspUrl + "/" + path + "?token=" + sign(path, clock.instant().plusSeconds(60));
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

    private String sourceUrl(Device device, String rtspUrl) {
        hostPolicy.requireAllowedRtsp(rtspUrl);
        String envName = device.getConfig().get("rtspCredentialsEnv");
        String credentials = envName == null ? null : secrets.get(envName);
        if (credentials == null || credentials.isBlank()) {
            return rtspUrl;
        }
        int i = rtspUrl.indexOf("://") + 3;
        return rtspUrl.substring(0, i) + credentials + "@" + rtspUrl.substring(i);
    }

    private String sign(String path, Instant expiresAt) {
        long exp = expiresAt.getEpochSecond();
        return exp + "." + mac(path + "|" + exp);
    }

    private boolean valid(String path, String token) {
        int dot = token.indexOf('.');
        if (dot < 1) {
            return false;
        }
        long exp;
        try {
            exp = Long.parseLong(token.substring(0, dot));
        } catch (NumberFormatException e) {
            return false;
        }
        return exp > clock.instant().getEpochSecond()
                && constantTimeEquals(mac(path + "|" + exp), token.substring(dot + 1));
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
