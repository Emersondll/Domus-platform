package br.com.domus.platform.shared.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Limitação de taxa (janela fixa de 60 s) e bloqueio por força bruta em credenciais de máquina.
 * Regras: API do agente por agente; ingestão de eventos por dispositivo; validação de credenciais por IP; demais
 * endpoints por IP. Como a identidade informada por máquinas (X-Agent-Id, id do dispositivo na URL) é controlada pelo
 * cliente, cada uma dessas chaves também passa por um teto por IP ({@value #MACHINE_IP_FACTOR} vezes o limite), para que
 * variar o identificador não contorne o limite. Requisições com token Bearer usam um teto por IP bem mais alto
 * ({@code bearer-per-ip-per-minute}): atrás de um mesmo NAT há muitos operadores e moradores, e o limite fino por pessoa é do
 * {@link UserRateLimitFilter}, que só conhece o usuário depois da autenticação.
 * Falhas repetidas de autenticação (401/403) nas APIs de agente/dispositivo bloqueiam a dupla IP + identificador por 5 min
 * (um equipamento com chave errada não bloqueia os outros do mesmo condomínio, que saem pelo mesmo IP); um teto maior de
 * falhas por IP ({@value #IP_FAILURE_FACTOR} vezes) ainda barra quem testa vários identificadores.
 * Estado em memória: vale por instância. Com mais de uma instância, use um armazenamento compartilhado (ex.: Redis);
 * o login de pessoas (senha/MFA) é protegido pelo provedor de identidade (Keycloak), não por este filtro.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class RateLimitFilter extends OncePerRequestFilter {

    private static final long WINDOW_MS = 60_000;
    private static final long LOCKOUT_MS = 300_000;
    private static final Pattern DEVICE_EVENTS = Pattern.compile("^/api/v1/devices/([^/]+)/events$");
    private static final int MAX_KEYS = 100_000;
    /** Teto por IP da identidade de máquina, em múltiplos do limite por identidade. */
    static final int MACHINE_IP_FACTOR = 10;
    /** Falhas de autenticação por IP, em múltiplos do limite por dupla IP + identificador. */
    static final int IP_FAILURE_FACTOR = 10;
    private static final Pattern UUID_TEXT = Pattern.compile(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

    private record Window(long start, int count) {
    }

    private final Clock clock;
    private final boolean enabled;
    private final boolean trustForwardedFor;
    private final int agentPerMin;
    private final int devicePerMin;
    private final int validatePerMin;
    private final int apiPerMin;
    private final int maxAuthFailures;
    private final int bearerPerIp;
    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Window> failures = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> blockedUntil = new ConcurrentHashMap<>();
    /** Instante da última varredura de entradas vencidas; só otimiza a frequência, então a corrida entre threads é inofensiva. */
    private volatile long lastSweep;

    /** Cria o filtro com o relógio e os limites por minuto lidos da configuração. */
    public RateLimitFilter(Clock clock,
            @Value("${portaria.rate-limit.enabled:true}") boolean enabled,
            @Value("${portaria.rate-limit.trust-forwarded-for:false}") boolean trustForwardedFor,
            @Value("${portaria.rate-limit.agent-per-minute:120}") int agentPerMin,
            @Value("${portaria.rate-limit.device-per-minute:120}") int devicePerMin,
            @Value("${portaria.rate-limit.validate-per-minute:120}") int validatePerMin,
            @Value("${portaria.rate-limit.api-per-minute:600}") int apiPerMin,
            @Value("${portaria.rate-limit.max-auth-failures:10}") int maxAuthFailures,
            @Value("${portaria.rate-limit.bearer-per-ip-per-minute:6000}") int bearerPerIp) {
        this.clock = clock;
        this.enabled = enabled;
        this.trustForwardedFor = trustForwardedFor;
        this.agentPerMin = agentPerMin;
        this.devicePerMin = devicePerMin;
        this.validatePerMin = validatePerMin;
        this.apiPerMin = apiPerMin;
        this.maxAuthFailures = maxAuthFailures;
        this.bearerPerIp = bearerPerIp;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !enabled || !request.getRequestURI().startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        long now = clock.millis();
        String path = request.getRequestURI();
        String ip = clientIp(request);
        boolean machine = path.startsWith("/api/v1/agent/") || DEVICE_EVENTS.matcher(path).matches();

        String identity = "-"; // quem a máquina diz ser (só vale para separar bloqueios; não é prova de nada)
        String key;
        int limit;
        Matcher dev = DEVICE_EVENTS.matcher(path);
        if (path.startsWith("/api/v1/agent/")) {
            String agent = request.getHeader("X-Agent-Id");
            boolean valid = agent != null && UUID_TEXT.matcher(agent).matches();
            identity = valid ? agent : "-";
            key = "agent:" + (valid ? agent : ip);
            limit = agentPerMin;
        } else if (dev.matches()) {
            identity = dev.group(1);
            key = "device:" + identity;
            limit = devicePerMin;
        } else if (path.endsWith("/access/validate")) {
            key = "validate:" + ip;
            limit = validatePerMin;
        } else if (hasBearer(request)) {
            key = "bearer:" + ip;
            limit = bearerPerIp;
        } else {
            key = "api:" + ip;
            limit = apiPerMin;
        }
        String failKey = "fail:" + ip + ":" + identity;
        String failIpKey = "failip:" + ip;
        if (machine) {
            String blockedKey = isBlocked(failKey, now) ? failKey : isBlocked(failIpKey, now) ? failIpKey : null;
            if (blockedKey != null) {
                reject(response, 429, retryAfter(blockedUntil.get(blockedKey), now));
                return;
            }
        }
        // o identificador de máquina é informado pelo cliente: o teto por IP impede contornar o limite trocando o valor
        if (machine && !tryAcquire("machine-ip:" + ip, limit * MACHINE_IP_FACTOR, now)) {
            reject(response, 429, WINDOW_MS / 1000);
            return;
        }
        if (!tryAcquire(key, limit, now)) {
            reject(response, 429, WINDOW_MS / 1000);
            return;
        }
        chain.doFilter(request, response);
        int status = response.getStatus();
        if (machine && (status == 401 || status == 403)) {
            recordFailure(failKey, maxAuthFailures, now);
            recordFailure(failIpKey, maxAuthFailures * IP_FAILURE_FACTOR, now);
        }
    }

    private static boolean hasBearer(HttpServletRequest request) {
        String auth = request.getHeader("Authorization");
        return auth != null && auth.regionMatches(true, 0, "Bearer ", 0, 7);
    }

    private boolean tryAcquire(String key, int limit, long now) {
        prune(now);
        Window w = windows.compute(key, (k, old) -> old == null || now - old.start >= WINDOW_MS
                ? new Window(now, 1) : new Window(old.start, old.count + 1));
        return w.count <= limit;
    }

    private void recordFailure(String key, int threshold, long now) {
        Window w = failures.compute(key, (k, old) -> old == null || now - old.start >= LOCKOUT_MS
                ? new Window(now, 1) : new Window(old.start, old.count + 1));
        if (w.count >= threshold) {
            blockedUntil.put(key, now + LOCKOUT_MS);
            failures.remove(key);
        }
    }

    private boolean isBlocked(String key, long now) {
        Long until = blockedUntil.get(key);
        if (until == null) {
            return false;
        }
        if (now >= until) {
            blockedUntil.remove(key);
            return false;
        }
        return true;
    }

    /**
     * Remove entradas vencidas das três tabelas, no máximo uma vez por janela (ou antes, se a tabela de janelas passar do
     * teto). Sem isso, {@code failures} e {@code blockedUntil} só encolhiam quando a mesma origem voltava, e uma varredura
     * de origens diferentes fazia a memória crescer sem limite.
     */
    private void prune(long now) {
        if (now - lastSweep < WINDOW_MS && windows.size() <= MAX_KEYS) {
            return;
        }
        lastSweep = now;
        windows.entrySet().removeIf(e -> now - e.getValue().start >= WINDOW_MS);
        failures.entrySet().removeIf(e -> now - e.getValue().start >= LOCKOUT_MS);
        blockedUntil.entrySet().removeIf(e -> e.getValue() <= now);
    }

    /** Quantidade de chaves guardadas nas três tabelas (usado nos testes). */
    int trackedKeys() {
        return windows.size() + failures.size() + blockedUntil.size();
    }

    private long retryAfter(Long until, long now) {
        return until == null ? 1 : Math.max(1, (until - now) / 1000);
    }

    private String clientIp(HttpServletRequest request) {
        if (trustForwardedFor) {
            String xff = request.getHeader("X-Forwarded-For");
            if (xff != null && !xff.isBlank()) {
                return xff.split(",")[0].trim();
            }
        }
        return request.getRemoteAddr();
    }

    private void reject(HttpServletResponse response, int status, long retryAfterSeconds) throws IOException {
        response.setStatus(status);
        response.setHeader("Retry-After", Long.toString(retryAfterSeconds));
        response.setContentType("application/problem+json");
        response.getOutputStream().write(("{\"type\":\"https://portaria.example/problems/rate-limited\","
                + "\"title\":\"Too Many Requests\",\"status\":429}").getBytes(StandardCharsets.UTF_8));
    }
}
