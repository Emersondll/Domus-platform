package br.com.domus.platform.identity.controller;

import br.com.domus.platform.identity.entity.AssociationRequest;
import br.com.domus.platform.identity.entity.AssociationStatus;
import br.com.domus.platform.identity.service.AssociationService;
import br.com.domus.platform.residents.entity.ResidentType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Associação do morador ao condomínio: código de adesão, pedido da pessoa e decisão do síndico. Sem regra de negócio. */
@RestController
public class AssociationController {

    private final AssociationService service;

    /** Construtor de AssociationController; recebe as dependências por injeção. */
    public AssociationController(AssociationService service) {
        this.service = service;
    }

    /** @param code código de adesão vigente; nulo se não houver */
    public record JoinCodeResponse(String code) {
    }

    /**
     * @param code     código de adesão informado pelo síndico
     * @param fullName nome completo
     * @param email    e-mail
     * @param phone    telefone, opcional
     * @param type     OWNER ou TENANT; dependentes não têm conta
     * @param unitText unidade como a pessoa a descreve (ex.: "Bloco A, 101")
     */
    public record AssociationRequestBody(@NotBlank @Size(max = 20) String code, @NotBlank @Size(max = 160) String fullName,
                                         @NotBlank @Email @Size(max = 190) String email, @Size(max = 30) String phone,
                                         @NotNull ResidentType type, @NotBlank @Size(max = 60) String unitText) {
    }

    /** @param unitId unidade real onde o morador será cadastrado */
    public record ApproveBody(@NotNull UUID unitId) {
    }

    /**
     * @param id        identificador do pedido
     * @param fullName  nome informado
     * @param email     e-mail informado
     * @param phone     telefone informado
     * @param type      vínculo pedido
     * @param unitText  unidade como descrita pela pessoa
     * @param status    PENDING, APPROVED ou REJECTED
     * @param createdAt quando foi pedido
     */
    public record AssociationResponse(UUID id, String fullName, String email, String phone, ResidentType type,
                                      String unitText, AssociationStatus status, Instant createdAt) {
        static AssociationResponse from(AssociationRequest r) {
            return new AssociationResponse(r.getId(), r.getFullName(), r.getEmail(), r.getPhone(),
                    r.getRequestedType(), r.getUnitText(), r.getStatus(), r.getCreatedAt());
        }
    }

    /**
     * @param id              identificador do pedido
     * @param condominiumName condomínio ao qual o pedido se refere
     * @param status          PENDING, APPROVED ou REJECTED
     * @param createdAt       quando foi pedido
     */
    public record MyAssociationResponse(UUID id, String condominiumName, AssociationStatus status, Instant createdAt) {
    }

    /** Atende `GET /condominiums/{condominiumId}/join-code`. */
    @GetMapping("/api/v1/condominiums/{condominiumId}/join-code")
    public JoinCodeResponse joinCode(@PathVariable UUID condominiumId) {
        return new JoinCodeResponse(service.joinCode(condominiumId).orElse(null));
    }

    /** Atende `POST /condominiums/{condominiumId}/join-code`: gera ou troca o código. */
    @PostMapping("/api/v1/condominiums/{condominiumId}/join-code")
    public JoinCodeResponse rotate(@PathVariable UUID condominiumId) {
        return new JoinCodeResponse(service.rotateJoinCode(condominiumId));
    }

    /** Atende `POST /condominiums/{condominiumId}/join-code/disable`. */
    @PostMapping("/api/v1/condominiums/{condominiumId}/join-code/disable")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void disable(@PathVariable UUID condominiumId) {
        service.disableJoinCode(condominiumId);
    }

    /** Atende `POST /association-requests`: a pessoa autenticada pede para entrar no condomínio do código. */
    @PostMapping("/api/v1/association-requests")
    @ResponseStatus(HttpStatus.CREATED)
    public MyAssociationResponse request(@Valid @RequestBody AssociationRequestBody r) {
        AssociationService.Mine m = service.request(r.code(), r.fullName(), r.email(), r.phone(), r.type(), r.unitText());
        return new MyAssociationResponse(m.request().getId(), m.condominiumName(), m.request().getStatus(),
                m.request().getCreatedAt());
    }

    /** Atende `GET /me/association-requests`: pedidos da própria pessoa, mesmo sem acesso ainda. */
    @GetMapping("/api/v1/me/association-requests")
    public List<MyAssociationResponse> mine() {
        return service.mine().stream().map(m -> new MyAssociationResponse(m.request().getId(), m.condominiumName(),
                m.request().getStatus(), m.request().getCreatedAt())).toList();
    }

    /** Atende `GET /condominiums/{condominiumId}/association-requests` (pendentes, ou a situação pedida). */
    @GetMapping("/api/v1/condominiums/{condominiumId}/association-requests")
    public List<AssociationResponse> list(@PathVariable UUID condominiumId,
                                          @RequestParam(required = false) AssociationStatus status) {
        return service.list(condominiumId, status).stream().map(AssociationResponse::from).toList();
    }

    /** Atende `POST /condominiums/{condominiumId}/association-requests/{requestId}/approve`. */
    @PostMapping("/api/v1/condominiums/{condominiumId}/association-requests/{requestId}/approve")
    public AssociationResponse approve(@PathVariable UUID condominiumId, @PathVariable UUID requestId,
                                       @Valid @RequestBody ApproveBody b) {
        return AssociationResponse.from(service.approve(condominiumId, requestId, b.unitId()));
    }

    /** Atende `POST /condominiums/{condominiumId}/association-requests/{requestId}/reject`. */
    @PostMapping("/api/v1/condominiums/{condominiumId}/association-requests/{requestId}/reject")
    public AssociationResponse reject(@PathVariable UUID condominiumId, @PathVariable UUID requestId) {
        return AssociationResponse.from(service.reject(condominiumId, requestId));
    }
}
