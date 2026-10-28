package br.com.domus.platform.reservations.service;

import br.com.domus.platform.reservations.entity.CommonArea;
import br.com.domus.platform.reservations.entity.Reservation;
import br.com.domus.platform.reservations.repository.CommonAreaRepository;
import br.com.domus.platform.reservations.repository.ReservationRepository;
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
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Áreas comuns e reservas. A ausência de sobreposição é garantida pela exclusion constraint do banco,
 * portanto vale sob concorrência; a violação vira 409.
 */
@Service
public class ReservationService {

    static final Duration MAX_DURATION = Duration.ofHours(12);

    private final CommonAreaRepository areas;
    private final ReservationRepository reservations;
    private final ResidentRepository residents;
    private final AccessService access;
    private final AuditService audit;
    private final Clock clock;

    /** Construtor de ReservationService; recebe as dependências por injeção. */
    public ReservationService(CommonAreaRepository areas, ReservationRepository reservations,
                              ResidentRepository residents, AccessService access, AuditService audit,
                              Clock clock) {
        this.areas = areas;
        this.reservations = reservations;
        this.residents = residents;
        this.access = access;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional
    /**
     * Cadastra uma área comum reservável.
     *
     * @param condominiumId condomínio
     * @param name nome
     * @param capacity capacidade
     */
    public CommonArea createArea(UUID condominiumId, String name, int capacity) {
        Actor actor = access.require(condominiumId, AccessLevel.MANAGE, "area.create");
        if (areas.existsByCondominiumIdAndName(condominiumId, name)) {
            throw new ConflictException("Common area already exists");
        }
        CommonArea area = areas.save(new CommonArea(condominiumId, name, capacity));
        audit.record(actor.userId(), null, condominiumId, "area.create", "common_area",
                area.getId().toString(), AuditOutcome.SUCCESS);
        return area;
    }

    @Transactional(readOnly = true)
    /**
     * Lista as áreas comuns do condomínio.
     *
     * @param condominiumId condomínio
     * @param pageable paginação
     * @return página de resultados
     */
    public Page<CommonArea> listAreas(UUID condominiumId, Pageable pageable) {
        access.require(condominiumId, AccessLevel.READ, "area.list");
        return areas.findByCondominiumId(condominiumId, pageable);
    }

    @Transactional(readOnly = true)
    /**
     * Lista as áreas comuns para o morador autenticado.
     *
     * @param condominiumId condomínio
     * @param pageable paginação
     * @return página de resultados
     */
    public Page<CommonArea> listAreasAsResident(UUID condominiumId, Pageable pageable) {
        access.requireResident(condominiumId, "area.list.self");
        return areas.findByCondominiumId(condominiumId, pageable);
    }

    @Transactional
    /**
     * Reserva a área no intervalo pedido; sobreposição devolve conflito (409).
     *
     * @param condominiumId condomínio
     * @param areaId área comum
     * @param startAt início
     * @param endAt fim
     */
    public Reservation reserve(UUID condominiumId, UUID areaId, Instant startAt, Instant endAt) {
        ResidentContext me = access.requireResident(condominiumId, "reservation.create");
        validateWindow(startAt, endAt);
        // Trava a área: duas reservas concorrentes do mesmo espaço entravam em deadlock na verificação da exclusion constraint
        // (o Postgres abortava uma com 500). Serializadas, a segunda recebe a violação limpa e vira 409.
        areas.findWithLockByIdAndCondominiumId(areaId, condominiumId)
                .orElseThrow(() -> new NotFoundException("Common area not found"));
        UUID unitId = residents.findByIdAndCondominiumId(me.residentId(), condominiumId).orElseThrow().getUnitId();
        // saveAndFlush: a violação da exclusion constraint precisa surgir aqui, dentro do caso de uso.
        Reservation saved = reservations.saveAndFlush(new Reservation(condominiumId, areaId, unitId, startAt, endAt));
        audit.record(me.actor().userId(), null, condominiumId, "reservation.create", "reservation",
                saved.getId().toString(), AuditOutcome.SUCCESS);
        return saved;
    }

    @Transactional
    /**
     * Cancela uma reserva do morador autenticado.
     *
     * @param condominiumId condomínio
     * @param reservationId reserva
     */
    public Reservation cancelAsResident(UUID condominiumId, UUID reservationId) {
        ResidentContext me = access.requireResident(condominiumId, "reservation.cancel.self");
        UUID unitId = residents.findByIdAndCondominiumId(me.residentId(), condominiumId).orElseThrow().getUnitId();
        Reservation reservation = reservations.findByIdAndCondominiumId(reservationId, condominiumId)
                .filter(r -> r.getUnitId().equals(unitId))
                .orElseThrow(() -> new NotFoundException("Reservation not found"));
        return cancel(reservation, me.actor().userId(), condominiumId);
    }

    @Transactional
    /**
     * Cancela uma reserva como equipe do condomínio.
     *
     * @param condominiumId condomínio
     * @param reservationId reserva
     */
    public Reservation cancelAsStaff(UUID condominiumId, UUID reservationId) {
        Actor actor = access.require(condominiumId, AccessLevel.OPERATE, "reservation.cancel");
        Reservation reservation = reservations.findByIdAndCondominiumId(reservationId, condominiumId)
                .orElseThrow(() -> new NotFoundException("Reservation not found"));
        return cancel(reservation, actor.userId(), condominiumId);
    }

    @Transactional(readOnly = true)
    /**
     * Lista as reservas do condomínio.
     *
     * @param condominiumId condomínio
     * @param pageable paginação
     * @return página de resultados
     */
    public Page<Reservation> list(UUID condominiumId, Pageable pageable) {
        access.require(condominiumId, AccessLevel.READ, "reservation.list");
        return reservations.findByCondominiumId(condominiumId, pageable);
    }

    @Transactional(readOnly = true)
    /**
     * Lista as reservas do morador autenticado.
     *
     * @param condominiumId condomínio
     * @param pageable paginação
     * @return página de resultados
     */
    public Page<Reservation> listMine(UUID condominiumId, Pageable pageable) {
        ResidentContext me = access.requireResident(condominiumId, "reservation.list.self");
        UUID unitId = residents.findByIdAndCondominiumId(me.residentId(), condominiumId).orElseThrow().getUnitId();
        return reservations.findByCondominiumIdAndUnitId(condominiumId, unitId, pageable);
    }

    private Reservation cancel(Reservation reservation, UUID userId, UUID condominiumId) {
        if (Reservation.CANCELLED.equals(reservation.getStatus())) {
            throw new ConflictException("Reservation already cancelled");
        }
        reservation.cancel();
        audit.record(userId, null, condominiumId, "reservation.cancel", "reservation",
                reservation.getId().toString(), AuditOutcome.SUCCESS);
        return reservation;
    }

    private void validateWindow(Instant startAt, Instant endAt) {
        Instant now = Instant.now(clock);
        if (!endAt.isAfter(startAt) || startAt.isBefore(now) || Duration.between(startAt, endAt).compareTo(MAX_DURATION) > 0) {
            throw new IllegalArgumentException("Invalid reservation window");
        }
    }
}
