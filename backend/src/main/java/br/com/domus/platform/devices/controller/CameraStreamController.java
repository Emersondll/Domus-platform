package br.com.domus.platform.devices.controller;

import br.com.domus.platform.devices.service.CameraStreamService;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Vídeo ao vivo das câmeras (HLS) e a chamada de autorização do servidor de mídia. Sem regra de negócio. */
@RestController
public class CameraStreamController {

    private final CameraStreamService streams;

    /** Construtor de CameraStreamController; recebe as dependências por injeção. */
    public CameraStreamController(CameraStreamService streams) {
        this.streams = streams;
    }

    /**
     * @param hlsUrl    URL HLS da câmera, já com o token da sessão
     * @param expiresAt instante em que o token deixa de valer; o painel pede outra sessão antes disso
     */
    public record StreamSessionResponse(String hlsUrl, Instant expiresAt) {
    }

    /**
     * Pedido do MediaMTX (somente campos usados).
     *
     * @param action   read, publish, playback...
     * @param path     caminho do fluxo
     * @param query    query string da requisição original (contém o token)
     * @param user     usuário informado por quem publica ({@code agent-<id>} para o agente local)
     * @param password senha informada por quem publica
     */
    public record MediaAuthRequest(String action, String path, String query, String user, String password) {
    }

    /**
     * Atende `POST /api/v1/condominiums/{condominiumId}/devices/{deviceId}/stream-session`.
     *
     * @param condominiumId condomínio
     * @param deviceId dispositivo
     */
    @PostMapping("/api/v1/condominiums/{condominiumId}/devices/{deviceId}/stream-session")
    public StreamSessionResponse open(@PathVariable UUID condominiumId, @PathVariable UUID deviceId) {
        CameraStreamService.Session s = streams.open(condominiumId, deviceId);
        return new StreamSessionResponse(s.hlsUrl(), s.expiresAt());
    }

    /**
     * @param id       identificador da câmera
     * @param name     nome
     * @param entrance entrada onde fica
     */
    public record ResidentCameraResponse(UUID id, String name, String entrance) {
    }

    /** Câmeras de entrada que o síndico liberou para o morador (vazio se nenhuma). */
    @GetMapping("/api/v1/condominiums/{condominiumId}/me/cameras")
    public List<ResidentCameraResponse> myCameras(@PathVariable UUID condominiumId) {
        return streams.camerasForResident(condominiumId).stream()
                .map(d -> new ResidentCameraResponse(d.getId(), d.getName(), d.getEntrance())).toList();
    }

    /**
     * Atende `POST /api/v1/condominiums/{condominiumId}/me/cameras/{deviceId}/stream-session`.
     *
     * @param condominiumId condomínio
     * @param deviceId dispositivo
     */
    @PostMapping("/api/v1/condominiums/{condominiumId}/me/cameras/{deviceId}/stream-session")
    public StreamSessionResponse openMine(@PathVariable UUID condominiumId, @PathVariable UUID deviceId) {
        CameraStreamService.Session s = streams.openForResident(condominiumId, deviceId);
        return new StreamSessionResponse(s.hlsUrl(), s.expiresAt());
    }

    /** Chamada do servidor de mídia (sem JWT; restrita por IP). 200 = permitido, 401 = negado. */
    @PostMapping("/internal/media/auth")
    public ResponseEntity<Void> mediaAuth(@RequestBody MediaAuthRequest r, HttpServletRequest request) {
        boolean ok = streams.authorize(request.getRemoteAddr(), r.action(), r.path(), r.query(), r.user(), r.password());
        return ResponseEntity.status(ok ? 200 : 401).build();
    }
}
