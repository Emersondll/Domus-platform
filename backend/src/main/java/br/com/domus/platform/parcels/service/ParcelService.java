package br.com.domus.platform.parcels.service;

import br.com.domus.platform.parcels.entity.Parcel;
import br.com.domus.platform.parcels.repository.ParcelRepository;
import br.com.domus.platform.commercial.service.CommercialService;
import br.com.domus.platform.notifications.service.NotificationService;
import br.com.domus.platform.residents.repository.ResidentRepository;
import br.com.domus.platform.shared.audit.AuditOutcome;
import br.com.domus.platform.shared.audit.AuditService;
import br.com.domus.platform.shared.error.ConflictException;
import br.com.domus.platform.shared.error.NotFoundException;
import br.com.domus.platform.shared.tenant.AccessLevel;
import br.com.domus.platform.shared.tenant.AccessService;
import br.com.domus.platform.shared.tenant.Actor;
import br.com.domus.platform.shared.tenant.ResidentContext;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Casos de uso de encomendas: registro e retirada na portaria, consulta pelo morador. */
@Service
public class ParcelService {

    private final ParcelRepository parcels;
    private final CommercialService commercial;
    private final ResidentRepository residents;
    private final AccessService access;
    private final AuditService audit;
    private final Clock clock;
    private final NotificationService notifications;

    /** Construtor de ParcelService; recebe as dependências por injeção. */
    public ParcelService(ParcelRepository parcels, CommercialService commercial, ResidentRepository residents,
                         AccessService access, AuditService audit, Clock clock,
                         NotificationService notifications) {
        this.parcels = parcels;
        this.commercial = commercial;
        this.residents = residents;
        this.access = access;
        this.audit = audit;
        this.clock = clock;
        this.notifications = notifications;
    }

    /**
     * Registra a chegada de uma encomenda para a unidade e avisa os moradores.
     *
     * @param condominiumId condomínio
     * @param unitId unidade
     * @param carrier transportadora
     * @param description descrição
     */
    @Transactional
    public Parcel register(UUID condominiumId, UUID unitId, String carrier, String description) {
        Actor actor = access.require(condominiumId, AccessLevel.OPERATE, "parcel.register");
        commercial.requireUnit(condominiumId, unitId);
        Parcel parcel = parcels.save(new Parcel(condominiumId, unitId, carrier, description, actor.userId(),
                Instant.now(clock)));
        audit.record(actor.userId(), null, condominiumId, "parcel.register", "parcel",
                parcel.getId().toString(), AuditOutcome.SUCCESS);
        notifications.notifyUnit(condominiumId, unitId, NotificationService.PARCEL_RECEIVED,
                "Encomenda recebida", carrier == null ? "Há uma encomenda na portaria." : "Encomenda de " + carrier + " na portaria.");
        return parcel;
    }

    /**
     * Registra a retirada da encomenda na portaria.
     *
     * @param condominiumId condomínio
     * @param parcelId encomenda
     * @param personName nome de quem retirou
     */
    @Transactional
    public Parcel pickUp(UUID condominiumId, UUID parcelId, String personName) {
        Actor actor = access.require(condominiumId, AccessLevel.OPERATE, "parcel.pickup");
        Parcel parcel = parcels.findByIdAndCondominiumId(parcelId, condominiumId)
                .orElseThrow(() -> new NotFoundException("Parcel not found"));
        if (Parcel.PICKED_UP.equals(parcel.getStatus())) {
            throw new ConflictException("Parcel already picked up");
        }
        parcel.pickUp(personName, Instant.now(clock));
        audit.record(actor.userId(), null, condominiumId, "parcel.pickup", "parcel",
                parcelId.toString(), AuditOutcome.SUCCESS);
        return parcel;
    }

    /**
     * Lista as encomendas do condomínio, com filtro opcional por estado.
     *
     * @param condominiumId condomínio
     * @param status estado
     * @param pageable paginação
     * @return página de resultados
     */
    @Transactional(readOnly = true)
    public Page<Parcel> list(UUID condominiumId, String status, Pageable pageable) {
        access.require(condominiumId, AccessLevel.READ, "parcel.list");
        return status == null ? parcels.findByCondominiumId(condominiumId, pageable)
                : parcels.findByCondominiumIdAndStatus(condominiumId, status, pageable);
    }

    /**
     * Lista as encomendas da unidade do morador autenticado.
     *
     * @param condominiumId condomínio
     * @param status estado
     * @param pageable paginação
     * @return página de resultados
     */
    @Transactional(readOnly = true)
    public Page<Parcel> listMine(UUID condominiumId, String status, Pageable pageable) {
        ResidentContext me = access.requireResident(condominiumId, "parcel.list.self");
        UUID unitId = residents.findByIdAndCondominiumId(me.residentId(), condominiumId).orElseThrow().getUnitId();
        return status == null ? parcels.findByCondominiumIdAndUnitId(condominiumId, unitId, pageable)
                : parcels.findByCondominiumIdAndUnitIdAndStatus(condominiumId, unitId, status, pageable);
    }

    /** Reenvia o aviso de uma encomenda que continua na portaria (lembrete ao morador). */
    @Transactional
    public Parcel remind(UUID condominiumId, UUID parcelId) {
        Actor actor = access.require(condominiumId, AccessLevel.OPERATE, "parcel.remind");
        Parcel parcel = parcels.findByIdAndCondominiumId(parcelId, condominiumId)
                .orElseThrow(() -> new NotFoundException("Parcel not found"));
        if (!Parcel.RECEIVED.equals(parcel.getStatus())) {
            throw new ConflictException("Parcel is not waiting for pickup");
        }
        audit.record(actor.userId(), null, condominiumId, "parcel.remind", "parcel", parcelId.toString(),
                AuditOutcome.SUCCESS);
        notifications.notifyUnit(condominiumId, parcel.getUnitId(), NotificationService.PARCEL_RECEIVED,
                "Lembrete: encomenda aguardando retirada",
                parcel.getCarrier() == null ? "Há uma encomenda esperando por você na portaria."
                        : "A encomenda de " + parcel.getCarrier() + " continua na portaria.");
        return parcel;
    }
}
