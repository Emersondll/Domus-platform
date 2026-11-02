package br.com.domus.platform.shared.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Set;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Descobre de onde veio a requisição para a trilha de auditoria, sem guardar IP nem dado pessoal: o canal (cabeçalho
 * {@code X-Domus-Client} enviado pelo painel e pelo app do morador; qualquer outro valor ou ausência vira {@code API}) e o tipo de
 * cliente (navegador ou outro). O canal é declarado pelo cliente: serve para distinguir clique de chamada manual, não é prova.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class ClientOriginFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Domus-Client";
    public static final String MDC_ORIGIN = "clientOrigin";
    public static final String MDC_KIND = "clientKind";
    private static final Set<String> DECLARED = Set.of("PANEL", "RESIDENT_APP");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String declared = request.getHeader(HEADER);
        String userAgent = request.getHeader("User-Agent");
        MDC.put(MDC_ORIGIN, declared != null && DECLARED.contains(declared) ? declared : "API");
        MDC.put(MDC_KIND, userAgent != null && userAgent.startsWith("Mozilla/") ? "BROWSER" : "OTHER");
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_ORIGIN);
            MDC.remove(MDC_KIND);
        }
    }
}
