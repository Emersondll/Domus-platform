package br.com.domus.platform.residentaccess.service;

import br.com.domus.platform.shared.error.InvalidRequestException;
import br.com.domus.platform.residentaccess.entity.PrivacyConsent;
import br.com.domus.platform.residentaccess.entity.ResidentPhoto;
import br.com.domus.platform.residentaccess.repository.PrivacyConsentRepository;
import br.com.domus.platform.residentaccess.repository.ResidentPhotoRepository;
import br.com.domus.platform.residents.entity.Resident;
import br.com.domus.platform.residents.entity.ResidentType;
import br.com.domus.platform.residents.repository.ResidentRepository;
import br.com.domus.platform.shared.audit.AuditOutcome;
import br.com.domus.platform.shared.audit.AuditService;
import br.com.domus.platform.shared.error.ConflictException;
import br.com.domus.platform.shared.error.NotFoundException;
import br.com.domus.platform.shared.image.JpegImages;
import br.com.domus.platform.shared.tenant.AccessLevel;
import br.com.domus.platform.shared.tenant.AccessService;
import br.com.domus.platform.shared.tenant.Actor;
import br.com.domus.platform.shared.tenant.ResidentContext;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Foto de cadastro do morador, que prepara o controle de acesso por rosto no futuro. Recurso desligado por padrão
 * ({@code portaria.resident-photo.enabled}): depende de decisão jurídica (D16). Só o titular vê a imagem; o consentimento é
 * específico, versionado e revogável, e revogar apaga a foto na hora sem tirar o acesso da pessoa. Não há detecção de
 * rosto nem modelo facial: é só uma imagem cadastral. Dependentes ficam fora desta versão.
 */
@Service
public class ResidentPhotoService {

    /** Versão do texto de consentimento. Mudar o texto exige nova versão e novo aceite. */
    public static final String TEXT_VERSION = "2026-10-v1";

    public static final String TEXT = """
            Autorizo o uso da minha foto de cadastro somente para o controle de acesso ao condomínio. A foto é guardada \
            cifrada, só eu a vejo e posso removê-la quando quiser: ao revogar, ela é apagada na hora. Recusar ou revogar \
            não impede minha entrada, pois há alternativa sem foto (cartão ou QR Code). Nesta versão a foto não é usada \
            para reconhecimento facial; isso só poderia ocorrer com um novo aviso e um novo aceite meu.""";

    static final int MAX_UPLOAD_BYTES = 2 * 1024 * 1024;
    static final int MIN_SIDE = 160;
    static final double MAX_ASPECT = 3.0;

    /** Situação da foto e do consentimento do morador. */
    public record Status(boolean enabled, String textVersion, String text, UUID consentId, boolean hasPhoto,
                         Instant photoCapturedAt) {
    }

    private final PrivacyConsentRepository consents;
    private final ResidentPhotoRepository photos;
    private final ResidentRepository residents;
    private final AccessService access;
    private final AuditService audit;
    private final Clock clock;
    private final boolean enabled;

    /** Construtor de ResidentPhotoService; recebe as dependências por injeção. */
    public ResidentPhotoService(PrivacyConsentRepository consents, ResidentPhotoRepository photos,
                                ResidentRepository residents, AccessService access, AuditService audit, Clock clock,
                                @Value("${portaria.resident-photo.enabled:false}") boolean enabled) {
        this.consents = consents;
        this.photos = photos;
        this.residents = residents;
        this.access = access;
        this.audit = audit;
        this.clock = clock;
        this.enabled = enabled;
    }

    /**
     * Informa se o recurso está ligado e o estado do consentimento e da foto do morador autenticado.
     *
     * @param condominiumId condomínio
     */
    @Transactional(readOnly = true)
    public Status status(UUID condominiumId) {
        ResidentContext me = access.requireResident(condominiumId, "resident.photo.status");
        Optional<PrivacyConsent> consent = consents.findFirstByResidentIdAndPurposeAndRevokedAtIsNull(
                me.residentId(), PrivacyConsent.ACCESS_PHOTO);
        Optional<ResidentPhoto> photo = photos.findByResidentIdAndCondominiumId(me.residentId(), condominiumId);
        return new Status(enabled, TEXT_VERSION, TEXT, consent.map(PrivacyConsent::getId).orElse(null),
                photo.isPresent(), photo.map(ResidentPhoto::getCapturedAt).orElse(null));
    }

    /**
     * Registra o consentimento específico do morador para o uso da foto.
     *
     * @param condominiumId condomínio
     * @param textVersion versão do texto de consentimento
     */
    @Transactional
    public PrivacyConsent grant(UUID condominiumId, String textVersion) {
        ResidentContext me = access.requireResident(condominiumId, "consent.grant");
        requireEnabled();
        requireAdult(condominiumId, me.residentId());
        if (!TEXT_VERSION.equals(textVersion)) {
            throw new ConflictException("The consent text changed; read it again before agreeing");
        }
        Optional<PrivacyConsent> current = consents.findFirstByResidentIdAndPurposeAndRevokedAtIsNull(
                me.residentId(), PrivacyConsent.ACCESS_PHOTO);
        if (current.isPresent()) {
            return current.get();
        }
        PrivacyConsent consent = consents.save(new PrivacyConsent(condominiumId, me.residentId(),
                PrivacyConsent.ACCESS_PHOTO, TEXT_VERSION, Instant.now(clock), me.actor().userId()));
        audit.record(me.actor().userId(), null, condominiumId, "consent.grant", "resident",
                me.residentId().toString(), AuditOutcome.SUCCESS);
        return consent;
    }

    /** Revoga o consentimento e apaga a foto na hora. */
    @Transactional
    public void revoke(UUID condominiumId, UUID consentId) {
        ResidentContext me = access.requireResident(condominiumId, "consent.revoke");
        PrivacyConsent consent = consents.findByIdAndResidentId(consentId, me.residentId())
                .orElseThrow(() -> new NotFoundException("Consent not found"));
        consent.revoke(Instant.now(clock));
        photos.deleteByResidentId(me.residentId());
        audit.record(me.actor().userId(), null, condominiumId, "consent.revoke", "resident",
                me.residentId().toString(), AuditOutcome.SUCCESS);
    }

    /**
     * Valida, cifra e guarda a foto enviada pelo morador.
     *
     * @param condominiumId condomínio
     * @param raw bytes da imagem enviada
     */
    @Transactional
    public Instant upload(UUID condominiumId, byte[] raw) {
        ResidentContext me = access.requireResident(condominiumId, "resident.photo.upload");
        requireEnabled();
        requireAdult(condominiumId, me.residentId());
        PrivacyConsent consent = consents.findFirstByResidentIdAndPurposeAndRevokedAtIsNull(me.residentId(),
                PrivacyConsent.ACCESS_PHOTO).orElseThrow(() -> new ConflictException("Consent is required"));
        if (raw == null || raw.length == 0 || raw.length > MAX_UPLOAD_BYTES) {
            throw new InvalidRequestException("Invalid image size");
        }
        byte[] jpeg = JpegImages.normalize(raw, MIN_SIDE, MAX_ASPECT);
        Instant now = Instant.now(clock);
        photos.findByResidentIdAndCondominiumId(me.residentId(), condominiumId).ifPresentOrElse(
                p -> p.replace(jpeg, now, me.actor().userId(), consent.getId()),
                () -> photos.save(new ResidentPhoto(condominiumId, me.residentId(), jpeg, now,
                        me.actor().userId(), consent.getId())));
        audit.record(me.actor().userId(), null, condominiumId, "resident.photo.upload", "resident",
                me.residentId().toString(), AuditOutcome.SUCCESS);
        return now;
    }

    /**
     * Devolve a foto do morador autenticado e registra a visualização na auditoria.
     *
     * @param condominiumId condomínio
     */
    @Transactional
    public byte[] view(UUID condominiumId) {
        ResidentContext me = access.requireResident(condominiumId, "resident.photo.view.self");
        requireEnabled();
        byte[] jpeg = photos.findByResidentIdAndCondominiumId(me.residentId(), condominiumId)
                .orElseThrow(() -> new NotFoundException("Photo not found")).jpeg();
        audit.record(me.actor().userId(), null, condominiumId, "resident.photo.view.self", "resident",
                me.residentId().toString(), AuditOutcome.SUCCESS);
        return jpeg;
    }

    /**
     * Apaga a foto do morador autenticado.
     *
     * @param condominiumId condomínio
     */
    @Transactional
    public void delete(UUID condominiumId) {
        ResidentContext me = access.requireResident(condominiumId, "resident.photo.delete");
        photos.deleteByResidentId(me.residentId());
        audit.record(me.actor().userId(), null, condominiumId, "resident.photo.delete", "resident",
                me.residentId().toString(), AuditOutcome.SUCCESS);
    }

    /** Para a ficha do morador na portaria: só informa se há foto, nunca a imagem. */
    @Transactional(readOnly = true)
    public boolean hasPhoto(UUID condominiumId, UUID residentId) {
        access.require(condominiumId, AccessLevel.READ, "resident.photo.has");
        residents.findByIdAndCondominiumId(residentId, condominiumId)
                .orElseThrow(() -> new NotFoundException("Resident not found"));
        return photos.findByResidentIdAndCondominiumId(residentId, condominiumId).isPresent();
    }

    /** Anonimização do titular: apaga a foto e revoga o consentimento (a prova do aceite e da revogação permanece). */
    @Transactional
    public void eraseOf(UUID residentId) {
        photos.deleteByResidentId(residentId);
        Instant now = Instant.now(clock);
        consents.findByResidentIdOrderByGrantedAtDesc(residentId).forEach(c -> c.revoke(now));
    }

    /** Exportação: a foto em base64 (o titular tem direito ao que se guarda dele). */
    @Transactional(readOnly = true)
    public Optional<ResidentPhoto> photoOf(UUID condominiumId, UUID residentId) {
        return photos.findByResidentIdAndCondominiumId(residentId, condominiumId);
    }

    /**
     * Lista os consentimentos do morador (uso interno, para exportação e anonimização).
     *
     * @param residentId morador
     */
    @Transactional(readOnly = true)
    public java.util.List<PrivacyConsent> consentsOf(UUID residentId) {
        return consents.findByResidentIdOrderByGrantedAtDesc(residentId);
    }

    private void requireEnabled() {
        if (!enabled) {
            throw new ConflictException("Registration photo is not enabled in this installation");
        }
    }

    private void requireAdult(UUID condominiumId, UUID residentId) {
        Resident r = residents.findByIdAndCondominiumId(residentId, condominiumId).orElseThrow();
        if (r.getResidentType() == ResidentType.DEPENDENT) {
            throw new ConflictException("Dependents are not supported yet");
        }
    }
}
