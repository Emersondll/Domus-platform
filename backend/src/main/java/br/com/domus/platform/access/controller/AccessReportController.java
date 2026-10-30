package br.com.domus.platform.access.controller;

import br.com.domus.platform.access.service.AccessReportService;
import br.com.domus.platform.shared.PageResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** API do relatório de acessos, dos visitantes no local e do registro de saída. */
@RestController
@RequestMapping("/api/v1/condominiums/{condominiumId}/access")
public class AccessReportController {

    private final AccessReportService service;

    /** Construtor de AccessReportController; recebe as dependências por injeção. */
    public AccessReportController(AccessReportService service) {
        this.service = service;
    }

    /**
     * @param attemptId   entrada (tentativa) a que a linha se refere; base para registrar a saída
     * @param occurredAt  instante da tentativa
     * @param entrance    entrada utilizada
     * @param result      GRANTED ou DENIED
     * @param reason      motivo do resultado
     * @param unitId      unidade visitada, quando identificada
     * @param unitLabel   "Bloco / número" da unidade
     * @param visitorName visitante, quando identificado
     * @param exitedAt    saída registrada; nulo = ainda no local (ou negada)
     * @param exitTicket  número do ticket de saída; nulo em entradas negadas e nas anteriores ao ticket
     */
    public record AccessRowResponse(UUID attemptId, Instant occurredAt, String entrance, String result, String reason,
                                    UUID unitId, String unitLabel, String visitorName, Instant exitedAt,
                                    Integer exitTicket) {
        static AccessRowResponse from(AccessReportService.Row r) {
            return new AccessRowResponse(r.attemptId(), r.occurredAt(), r.entrance(), r.result(), r.reason(),
                    r.unitId(), r.unitLabel(), r.visitorName(), r.exitedAt(), r.exitTicket());
        }
    }

    /**
     * Atende `GET /condominiums/{condominiumId}/access/on-site`.
     *
     * @param condominiumId condomínio
     * @param pageable paginação
     * @return página no formato da API
     */
    @GetMapping("/on-site")
    public PageResponse<AccessRowResponse> onSite(@PathVariable UUID condominiumId,
            @PageableDefault(size = 50, sort = "occurredAt", direction = Sort.Direction.DESC) Pageable pageable) {
        return PageResponse.of(service.onSite(condominiumId, pageable), AccessRowResponse::from);
    }

    /**
     * Atende `GET /condominiums/{condominiumId}/access/report`.
     *
     * @param condominiumId condomínio
     * @param from início do período
     * @param to fim do período
     * @param result resultado
     * @param pageable paginação
     * @return página no formato da API
     */
    @GetMapping("/report")
    public PageResponse<AccessRowResponse> report(@PathVariable UUID condominiumId, @RequestParam Instant from,
            @RequestParam Instant to, @RequestParam(required = false) String result,
            @PageableDefault(size = 50, sort = "occurredAt", direction = Sort.Direction.DESC) Pageable pageable) {
        return PageResponse.of(service.report(condominiumId, from, to, result, pageable), AccessRowResponse::from);
    }

    /**
     * Atende `GET /condominiums/{condominiumId}/access/report.csv`.
     *
     * @param condominiumId condomínio
     * @param from início do período
     * @param to fim do período
     * @param result resultado
     */
    @GetMapping("/report.csv")
    public ResponseEntity<byte[]> reportCsv(@PathVariable UUID condominiumId, @RequestParam Instant from,
            @RequestParam Instant to, @RequestParam(required = false) String result) {
        byte[] body = service.exportCsv(condominiumId, from, to, result).getBytes(StandardCharsets.UTF_8);
        return ResponseEntity.ok()
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename("acessos.csv").build().toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(body);
    }

    /**
     * Atende `POST /condominiums/{condominiumId}/access/entries/{attemptId}/exit`.
     *
     * @param condominiumId condomínio
     * @param attemptId tentativa de acesso
     */
    @PostMapping("/entries/{attemptId}/exit")
    public AccessRowResponse registerExit(@PathVariable UUID condominiumId, @PathVariable UUID attemptId) {
        return AccessRowResponse.from(service.registerExit(condominiumId, attemptId));
    }

    /**
     * Atende `POST /condominiums/{condominiumId}/access/exit-tickets/{ticket}/validate`.
     *
     * @param condominiumId condomínio
     * @param ticket número do ticket de saída
     */
    @PostMapping("/exit-tickets/{ticket}/validate")
    public AccessRowResponse validateExitTicket(@PathVariable UUID condominiumId, @PathVariable int ticket) {
        return AccessRowResponse.from(service.validateExitTicket(condominiumId, ticket));
    }
}
