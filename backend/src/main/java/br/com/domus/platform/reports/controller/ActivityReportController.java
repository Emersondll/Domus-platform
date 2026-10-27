package br.com.domus.platform.reports.controller;

import br.com.domus.platform.reports.service.ActivityReportService;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** API do relatório de atividade (encomendas, atendimentos, ocorrências e livro de turno) por período de até 31 dias. */
@RestController
@RequestMapping("/api/v1/condominiums/{condominiumId}/reports")
public class ActivityReportController {

    private final ActivityReportService service;

    public ActivityReportController(ActivityReportService service) {
        this.service = service;
    }

    /** O fim do período é exclusivo. */
    @GetMapping("/activity")
    public ActivityReportService.Report activity(@PathVariable UUID condominiumId, @RequestParam Instant from,
                                                 @RequestParam Instant to) {
        return service.report(condominiumId, from, to);
    }

    @GetMapping("/activity.csv")
    public ResponseEntity<byte[]> activityCsv(@PathVariable UUID condominiumId, @RequestParam Instant from,
                                              @RequestParam Instant to) {
        byte[] body = service.csv(service.report(condominiumId, from, to)).getBytes(StandardCharsets.UTF_8);
        return ResponseEntity.ok()
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename("atividade.csv").build().toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(body);
    }
}
