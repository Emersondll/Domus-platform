package br.com.domus.platform.shared.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Limite de requisições por <b>pessoa</b> (janela fixa de 60 s), aplicado depois da autenticação, quando o {@code sub} do token
 * já foi verificado. Existe porque o limite por IP não distingue quem divide a mesma saída de rede (uma central com vários
 * operadores, moradores atrás do mesmo NAT): o IP só tem um teto alto contra inundação, e o controle fino é por usuário.
 * Estado em memória (por instância), como o {@link RateLimitFilter}. Não é um {@code @Component}: o contêiner de servlet o
 * registraria antes da segurança, quando ainda não há usuário; ele entra na cadeia de segurança (ver {@link SecurityConfig}).
 */
public class UserRateLimitFilter extends OncePerRequestFilter {

    private static final long WINDOW_MS = 60_000;
    private static final int MAX_KEYS = 100_000;

    private record Window(long start, int count) {
    }

    private final Clock clock;
    private final boolean enabled;
    private final int perMinute;
    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();
    private volatile long lastSweep;

    /** Cria o filtro com o relógio e o limite por usuário e por minuto. */
    public UserRateLimitFilter(Clock clock, boolean enabled, int perMinute) {
        this.clock = clock;
        this.enabled = enabled;
        this.perMinute = perMinute;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !enabled || !request.getRequestURI().startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || auth instanceof AnonymousAuthenticationToken) {
            chain.doFilter(request, response); // máquinas (agente, dispositivo) têm limite próprio no RateLimitFilter
            return;
        }
        long now = clock.millis();
        sweep(now);
        Window w = windows.compute(auth.getName(), (k, old) -> old == null || now - old.start >= WINDOW_MS
                ? new Window(now, 1) : new Window(old.start, old.count + 1));
        if (w.count > perMinute) {
            response.setStatus(429);
            response.setHeader("Retry-After", Long.toString(Math.max(1, (w.start + WINDOW_MS - now) / 1000)));
            response.setContentType("application/problem+json");
            response.getOutputStream().write(("{\"title\":\"Too Many Requests\",\"status\":429,"
                    + "\"detail\":\"Too many requests for this user; try again shortly\"}").getBytes(StandardCharsets.UTF_8));
            return;
        }
        chain.doFilter(request, response);
    }

    private void sweep(long now) {
        if (now - lastSweep < WINDOW_MS && windows.size() <= MAX_KEYS) {
            return;
        }
        lastSweep = now;
        windows.entrySet().removeIf(e -> now - e.getValue().start >= WINDOW_MS);
    }

    /** Quantidade de usuários guardados (usado nos testes). */
    int trackedKeys() {
        return windows.size();
    }
}
