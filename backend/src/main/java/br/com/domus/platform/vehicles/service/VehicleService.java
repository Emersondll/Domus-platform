package br.com.domus.platform.vehicles.service;

import br.com.domus.platform.vehicles.entity.Vehicle;
import br.com.domus.platform.vehicles.repository.VehicleRepository;
import br.com.domus.platform.shared.audit.AuditOutcome;
import br.com.domus.platform.shared.audit.AuditService;
import br.com.domus.platform.shared.crypto.FieldCipher;
import br.com.domus.platform.shared.error.ConflictException;
import br.com.domus.platform.shared.tenant.AccessLevel;
import br.com.domus.platform.shared.tenant.AccessService;
import br.com.domus.platform.shared.tenant.ResidentContext;
import br.com.domus.platform.residents.repository.ResidentRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Casos de uso de veículos: visão da portaria e autoatendimento do morador. */
@Service
public class VehicleService {

    private final VehicleRepository vehicles;
    private final ResidentRepository residents;
    private final AccessService access;
    private final AuditService audit;
    private final FieldCipher cipher;

    public VehicleService(VehicleRepository vehicles, ResidentRepository residents, AccessService access,
                          AuditService audit, FieldCipher cipher) {
        this.vehicles = vehicles;
        this.residents = residents;
        this.access = access;
        this.audit = audit;
        this.cipher = cipher;
    }

    /** Teto de veículos por condomínio para ordenar por placa em memória (a placa cifrada não ordena no banco). */
    static final int MAX_PLATE_SORT = 5000;

    /**
     * Lista os veículos. {@code plate} (opcional) busca por igualdade pelo índice cego. Ordenar por {@code plate} é feito em
     * memória sobre o texto decifrado, para valer em todas as páginas; acima de {@link #MAX_PLATE_SORT} veículos pede-se a
     * busca por placa em vez da ordenação.
     */
    @Transactional(readOnly = true)
    public Page<Vehicle> list(UUID condominiumId, String plate, Pageable pageable) {
        access.require(condominiumId, AccessLevel.READ, "vehicle.list");
        if (plate != null && !plate.isBlank()) {
            String hash = cipher.blindIndex(FieldCipher.normalizePlate(plate));
            return vehicles.findByCondominiumIdAndPlateHash(condominiumId, hash, pageable);
        }
        var plateOrder = pageable.getSort().getOrderFor("plate");
        if (plateOrder == null) {
            return vehicles.findByCondominiumId(condominiumId, pageable);
        }
        if (vehicles.countByCondominiumId(condominiumId) > MAX_PLATE_SORT) {
            throw new IllegalArgumentException("Too many vehicles to sort by plate; search by plate instead");
        }
        java.util.Comparator<Vehicle> byPlate = java.util.Comparator.comparing(Vehicle::getPlate);
        List<Vehicle> all = new java.util.ArrayList<>(vehicles.findByCondominiumId(condominiumId,
                org.springframework.data.domain.Pageable.unpaged()).getContent());
        all.sort(plateOrder.isAscending() ? byPlate : byPlate.reversed());
        int from = (int) Math.min(pageable.getOffset(), all.size());
        int to = Math.min(from + pageable.getPageSize(), all.size());
        return new org.springframework.data.domain.PageImpl<>(all.subList(from, to), pageable, all.size());
    }

    @Transactional(readOnly = true)
    public Page<Vehicle> listMine(UUID condominiumId, Pageable pageable) {
        ResidentContext me = access.requireResident(condominiumId, "vehicle.list.self");
        return vehicles.findByCondominiumIdAndUnitId(condominiumId, unitOf(me), pageable);
    }

    @Transactional
    public Vehicle registerMine(UUID condominiumId, String plate, String model, String color) {
        ResidentContext me = access.requireResident(condominiumId, "vehicle.create.self");
        String normalized = FieldCipher.normalizePlate(plate);
        String plateHash = cipher.blindIndex(normalized);
        if (vehicles.existsByCondominiumIdAndPlateHash(condominiumId, plateHash)) {
            throw new ConflictException("Vehicle plate already registered");
        }
        Vehicle vehicle = vehicles.save(new Vehicle(condominiumId, unitOf(me), normalized, plateHash, model, color));
        audit.record(me.actor().userId(), null, condominiumId, "vehicle.create", "vehicle",
                vehicle.getId().toString(), AuditOutcome.SUCCESS);
        return vehicle;
    }

    private UUID unitOf(ResidentContext me) {
        return residents.findByIdAndCondominiumId(me.residentId(), me.condominiumId())
                .orElseThrow().getUnitId();
    }
}
