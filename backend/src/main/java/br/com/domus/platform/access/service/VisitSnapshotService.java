package br.com.domus.platform.access.service;

import br.com.domus.platform.access.entity.VisitRequest;
import br.com.domus.platform.access.entity.VisitSnapshot;
import br.com.domus.platform.access.repository.VisitRequestRepository;
import br.com.domus.platform.access.repository.VisitSnapshotRepository;
import br.com.domus.platform.shared.EntranceCameraLookup;
import br.com.domus.platform.shared.audit.AuditOutcome;
import br.com.domus.platform.shared.audit.AuditService;
import br.com.domus.platform.shared.error.ConflictException;
import br.com.domus.platform.shared.error.NotFoundException;
import br.com.domus.platform.shared.tenant.AccessLevel;
import br.com.domus.platform.shared.tenant.AccessService;
import br.com.domus.platform.shared.tenant.Actor;
import br.com.domus.platform.shared.tenant.ResidentContext;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Imagem da câmera de entrada enviada junto do pedido de autorização. A portaria captura e vê a prévia antes de enviar; o
 * morador da unidade do pedido vê a imagem no app. Recurso desligado por padrão ({@code portaria.snapshots.enabled}): a
 * imagem de uma pessoa é dado sensível e depende de decisão jurídica (D13). Cada captura e cada visualização é auditada.
 */
@Service
public class VisitSnapshotService {

    /** Metadados da captura devolvidos à portaria. */
    public record Captured(UUID id, Instant expiresAt, String cameraName) {
    }

    private final VisitSnapshotRepository snapshots;
    private final VisitRequestRepository requests;
    private final EntranceCameraLookup cameras;
    private final AccessService access;
    private final AuditService audit;
    private final Clock clock;
    private final boolean enabled;
    private final Duration tempTtl;

    public VisitSnapshotService(VisitSnapshotRepository snapshots, VisitRequestRepository requests,
                                EntranceCameraLookup cameras, AccessService access, AuditService audit, Clock clock,
                                @Value("${portaria.snapshots.enabled:false}") boolean enabled,
                                @Value("${portaria.snapshots.temp-ttl:PT10M}") Duration tempTtl) {
        this.snapshots = snapshots;
        this.requests = requests;
        this.cameras = cameras;
        this.access = access;
        this.audit = audit;
        this.clock = clock;
        this.enabled = enabled;
        this.tempTtl = tempTtl;
    }

    /** Se o recurso está ligado e quais entradas têm câmera de entrada de visitas. */
    public record Availability(boolean enabled, List<String> entrances) {
    }

    @Transactional(readOnly = true)
    /**
     * Informa se a imagem da câmera de entrada está disponível para anexar ao pedido.
     *
     * @param condominiumId condomínio
     */
    public Availability availability(UUID condominiumId) {
        access.require(condominiumId, AccessLevel.OPERATE, "snapshot.availability");
        return new Availability(enabled, enabled ? cameras.visitorEntranceNames(condominiumId) : List.of());
    }

    /** Captura um quadro da câmera de entrada. A imagem fica temporária até ser enviada com um pedido. */
    @Transactional
    public Captured capture(UUID condominiumId, String entrance) {
        Actor actor = access.require(condominiumId, AccessLevel.OPERATE, "snapshot.capture");
        requireEnabled();
        EntranceCameraLookup.Capture frame = cameras.captureVisitorEntrance(condominiumId, entrance);
        Instant now = Instant.now(clock);
        VisitSnapshot snapshot = snapshots.save(new VisitSnapshot(condominiumId, frame.deviceId(), actor.userId(), now,
                now.plus(tempTtl), frame.jpeg()));
        audit.record(actor.userId(), null, condominiumId, "snapshot.capture", "visit_snapshot",
                snapshot.getId().toString(), AuditOutcome.SUCCESS);
        return new Captured(snapshot.getId(), snapshot.getExpiresAt(), frame.cameraName());
    }

    /** Prévia da imagem que o próprio operador capturou e ainda não enviou. */
    @Transactional(readOnly = true)
    public byte[] preview(UUID condominiumId, UUID snapshotId) {
        Actor actor = access.require(condominiumId, AccessLevel.OPERATE, "snapshot.preview");
        VisitSnapshot s = snapshots.findByIdAndCondominiumId(snapshotId, condominiumId)
                .filter(x -> x.getCapturedByUserId().equals(actor.userId()))
                .filter(x -> x.getVisitRequestId() != null || x.getExpiresAt().isAfter(Instant.now(clock)))
                .orElseThrow(() -> new NotFoundException("Image not found"));
        return s.jpeg();
    }

    /**
     * Vincula a imagem ao pedido recém-criado. Precisa ser do mesmo operador, do mesmo condomínio, não vencida e ainda não
     * vinculada. Chamado pela criação do pedido, na mesma transação.
     */
    @Transactional
    public void attach(UUID condominiumId, UUID snapshotId, UUID requestId, UUID actorUserId) {
        requireEnabled();
        VisitSnapshot s = snapshots.findByIdAndCondominiumId(snapshotId, condominiumId)
                .filter(x -> x.getCapturedByUserId().equals(actorUserId))
                .orElseThrow(() -> new NotFoundException("Image not found"));
        if (s.getVisitRequestId() != null) {
            throw new ConflictException("This image was already sent with another request");
        }
        if (!s.getExpiresAt().isAfter(Instant.now(clock))) {
            throw new ConflictException("This image expired; capture it again");
        }
        s.attachTo(requestId);
    }

    /** Dos pedidos informados, os que têm imagem. */
    @Transactional(readOnly = true)
    public Set<UUID> requestsWithSnapshot(UUID condominiumId, Collection<UUID> requestIds) {
        return requestIds.isEmpty() ? Set.of()
                : Set.copyOf(snapshots.findRequestIdsWithSnapshot(condominiumId, requestIds));
    }

    /** Imagem de um pedido, para a portaria. */
    @Transactional
    public byte[] imageForStaff(UUID condominiumId, UUID requestId) {
        Actor actor = access.require(condominiumId, AccessLevel.READ, "snapshot.view");
        byte[] jpeg = find(condominiumId, requestId).jpeg();
        audit.record(actor.userId(), null, condominiumId, "snapshot.view", "visit_request", requestId.toString(),
                AuditOutcome.SUCCESS);
        return jpeg;
    }

    /** Imagem de um pedido, para o morador da unidade visitada (qualquer outro recebe 404). */
    @Transactional
    public byte[] imageForResident(UUID condominiumId, UUID requestId, UUID residentUnitId) {
        ResidentContext me = access.requireResident(condominiumId, "snapshot.view.self");
        VisitRequest request = requests.findByIdAndCondominiumId(requestId, condominiumId)
                .filter(r -> r.getUnitId().equals(residentUnitId))
                .orElseThrow(() -> new NotFoundException("Image not found"));
        byte[] jpeg = find(condominiumId, request.getId()).jpeg();
        audit.record(me.actor().userId(), null, condominiumId, "snapshot.view.self", "visit_request",
                requestId.toString(), AuditOutcome.SUCCESS);
        return jpeg;
    }

    private VisitSnapshot find(UUID condominiumId, UUID requestId) {
        return snapshots.findByVisitRequestIdAndCondominiumId(requestId, condominiumId)
                .orElseThrow(() -> new NotFoundException("Image not found"));
    }

    private void requireEnabled() {
        if (!enabled) {
            throw new ConflictException("Image capture is not enabled on this installation");
        }
    }
}
