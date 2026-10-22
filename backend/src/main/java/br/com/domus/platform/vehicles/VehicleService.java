package br.com.domus.platform.vehicles;

import br.com.domus.platform.shared.audit.AuditOutcome;
import br.com.domus.platform.shared.audit.AuditService;
import br.com.domus.platform.shared.crypto.FieldCipher;
import br.com.domus.platform.shared.error.ConflictException;
import br.com.domus.platform.shared.tenant.AccessLevel;
import br.com.domus.platform.shared.tenant.AccessService;
import br.com.domus.platform.shared.tenant.ResidentContext;
import br.com.domus.platform.residents.ResidentRepository;
import java.util.Locale;
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

    @Transactional(readOnly = true)
    public Page<Vehicle> list(UUID condominiumId, Pageable pageable) {
        access.require(condominiumId, AccessLevel.READ, "vehicle.list");
        return vehicles.findByCondominiumId(condominiumId, pageable);
    }

    @Transactional(readOnly = true)
    public Page<Vehicle> listMine(UUID condominiumId, Pageable pageable) {
        ResidentContext me = access.requireResident(condominiumId, "vehicle.list.self");
        return vehicles.findByCondominiumIdAndUnitId(condominiumId, unitOf(me), pageable);
    }

    @Transactional
    public Vehicle registerMine(UUID condominiumId, String plate, String model, String color) {
        ResidentContext me = access.requireResident(condominiumId, "vehicle.create.self");
        String normalized = plate.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "");
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
