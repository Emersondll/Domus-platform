package br.com.domus.platform.privacy;

import br.com.domus.platform.access.AccessCredential;
import br.com.domus.platform.access.AccessCredentialRepository;
import br.com.domus.platform.identity.AppUser;
import br.com.domus.platform.identity.AppUserRepository;
import br.com.domus.platform.identity.Membership;
import br.com.domus.platform.identity.MembershipRepository;
import br.com.domus.platform.notifications.Notification;
import br.com.domus.platform.notifications.NotificationPreference;
import br.com.domus.platform.notifications.NotificationPreferenceRepository;
import br.com.domus.platform.notifications.NotificationRepository;
import br.com.domus.platform.parcels.Parcel;
import br.com.domus.platform.parcels.ParcelRepository;
import br.com.domus.platform.residents.Resident;
import br.com.domus.platform.residents.ResidentRepository;
import br.com.domus.platform.shared.PolicyVersion;
import br.com.domus.platform.shared.audit.AuditOutcome;
import br.com.domus.platform.shared.audit.AuditService;
import br.com.domus.platform.shared.error.NotFoundException;
import br.com.domus.platform.shared.tenant.AccessLevel;
import br.com.domus.platform.shared.tenant.AccessService;
import br.com.domus.platform.shared.tenant.Actor;
import br.com.domus.platform.vehicles.Vehicle;
import br.com.domus.platform.vehicles.VehicleRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Atendimento a direitos do titular (acesso/portabilidade e eliminação). É um serviço transversal e por isso lê e
 * altera dados de vários módulos. Ambas as operações exigem gestão do condomínio e ficam registradas na auditoria
 * (inclusive a consulta, por tratar dados pessoais). A decisão de atender ou negar um pedido é do controlador
 * dos dados (ver docs/lgpd.md); aqui está apenas o mecanismo técnico.
 */
@Service
public class PrivacyService {

    private final ResidentRepository residents;
    private final MembershipRepository memberships;
    private final AppUserRepository users;
    private final NotificationRepository notifications;
    private final NotificationPreferenceRepository preferences;
    private final VehicleRepository vehicles;
    private final ParcelRepository parcels;
    private final AccessCredentialRepository credentials;
    private final PolicyVersion policyVersion;
    private final AccessService access;
    private final AuditService audit;
    private final Clock clock;

    public PrivacyService(ResidentRepository residents, MembershipRepository memberships, AppUserRepository users,
                          NotificationRepository notifications, NotificationPreferenceRepository preferences,
                          VehicleRepository vehicles, ParcelRepository parcels, AccessCredentialRepository credentials,
                          PolicyVersion policyVersion, AccessService access, AuditService audit, Clock clock) {
        this.residents = residents;
        this.memberships = memberships;
        this.users = users;
        this.notifications = notifications;
        this.preferences = preferences;
        this.vehicles = vehicles;
        this.parcels = parcels;
        this.credentials = credentials;
        this.policyVersion = policyVersion;
        this.access = access;
        this.audit = audit;
        this.clock = clock;
    }

    /** Exportação dos dados pessoais do morador em formato estruturado. */
    @Transactional
    public Map<String, Object> export(UUID condominiumId, UUID residentId) {
        Actor actor = access.require(condominiumId, AccessLevel.MANAGE, "resident.export");
        Resident r = residents.findByIdAndCondominiumId(residentId, condominiumId)
                .orElseThrow(() -> new NotFoundException("Resident not found"));
        List<Membership> linked = memberships.findByResidentId(residentId);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("exportedAt", Instant.now(clock).toString());
        Map<String, Object> profile = new LinkedHashMap<>();
        profile.put("id", r.getId());
        profile.put("fullName", r.getFullName());
        profile.put("email", r.getEmail());
        profile.put("phone", r.getPhone());
        profile.put("residentType", r.getResidentType());
        profile.put("active", r.isActive());
        profile.put("unitId", r.getUnitId());
        out.put("resident", profile);
        out.put("vehiclesOfUnit", vehicles.findByCondominiumIdAndUnitId(condominiumId, r.getUnitId(), Pageable.unpaged())
                .map(Vehicle::getPlate).getContent());
        out.put("parcelsOfUnit", parcels.findByCondominiumIdAndUnitId(condominiumId, r.getUnitId(), Pageable.unpaged())
                .map(p -> Map.of("carrier", String.valueOf(p.getCarrier()), "status", p.getStatus(),
                        "receivedAt", p.getReceivedAt().toString())).getContent());
        for (Membership m : linked) {
            AppUser u = users.findById(m.getUserId()).orElse(null);
            if (u == null) {
                continue;
            }
            out.put("account", Map.of("email", u.getEmail(), "displayName", u.getDisplayName(), "active", u.isActive()));
            NotificationPreference pref = preferences.findById(u.getId()).orElse(null);
            out.put("preferences", pref == null ? Map.of() : Map.of("parcelsEnabled", pref.isParcelsEnabled(),
                    "visitorArrivalEnabled", pref.isVisitorArrivalEnabled()));
            out.put("notifications", notifications.findByUserId(u.getId()).stream().map(this::notification).toList());
            out.put("invitationsCreated", credentials.findByCondominiumIdAndCreatedByUserId(condominiumId, u.getId())
                    .stream().map(c -> Map.of("id", c.getId(), "validFrom", c.getValidFrom().toString(),
                            "validUntil", c.getValidUntil().toString(), "maxUses", c.getMaxUses(),
                            "useCount", c.getUseCount(), "status", c.getStatus())).toList());
        }
        audit.record(actor.userId(), null, condominiumId, "resident.export", "resident", residentId.toString(),
                AuditOutcome.SUCCESS);
        return out;
    }

    /**
     * Elimina os dados pessoais do morador: anonimiza cadastro e conta, revoga acessos e convites, apaga notificações
     * e preferências. Registros de segurança e auditoria (tentativas de acesso, comandos) são mantidos sem vínculo
     * direto a dados pessoais, conforme a política de retenção.
     */
    @Transactional
    public void anonymize(UUID condominiumId, UUID residentId) {
        Actor actor = access.require(condominiumId, AccessLevel.MANAGE, "resident.anonymize");
        Resident r = residents.findByIdAndCondominiumId(residentId, condominiumId)
                .orElseThrow(() -> new NotFoundException("Resident not found"));
        Instant now = Instant.now(clock);
        boolean revokedCredential = false;
        for (Membership m : memberships.findByResidentId(residentId)) {
            m.revoke();
            users.findById(m.getUserId()).ifPresent(u -> {
                notifications.deleteByUserId(u.getId());
                preferences.deleteById(u.getId());
                u.anonymize();
            });
            for (AccessCredential c : credentials.findByCondominiumIdAndCreatedByUserId(condominiumId, m.getUserId())) {
                if (AccessCredential.ACTIVE.equals(c.getStatus())) {
                    c.revoke(now);
                    revokedCredential = true;
                }
            }
        }
        r.anonymize();
        if (revokedCredential) {
            policyVersion.bump(condominiumId);
        }
        audit.record(actor.userId(), null, condominiumId, "resident.anonymize", "resident", residentId.toString(),
                AuditOutcome.SUCCESS);
    }

    private Map<String, Object> notification(Notification n) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", n.getType());
        m.put("title", n.getTitle());
        m.put("body", n.getBody());
        m.put("createdAt", n.getCreatedAt().toString());
        return m;
    }
}
