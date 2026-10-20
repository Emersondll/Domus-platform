package br.com.portaria.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.portaria.platform.commercial.Block;
import br.com.portaria.platform.commercial.BlockRepository;
import br.com.portaria.platform.commercial.Condominium;
import br.com.portaria.platform.commercial.CondominiumRepository;
import br.com.portaria.platform.commercial.OperatorCompany;
import br.com.portaria.platform.commercial.OperatorCompanyRepository;
import br.com.portaria.platform.commercial.Unit;
import br.com.portaria.platform.commercial.UnitRepository;
import br.com.portaria.platform.identity.AppUser;
import br.com.portaria.platform.identity.AppUserRepository;
import br.com.portaria.platform.identity.Membership;
import br.com.portaria.platform.identity.MembershipRepository;
import br.com.portaria.platform.identity.Role;
import br.com.portaria.platform.privacy.RetentionService;
import br.com.portaria.platform.residents.Resident;
import br.com.portaria.platform.residents.ResidentRepository;
import br.com.portaria.platform.residents.ResidentType;
import br.com.portaria.platform.shared.audit.AuditLogRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Direitos do titular (exportação, eliminação) e retenção de dados. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PrivacyIT extends AbstractIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired OperatorCompanyRepository companies;
    @Autowired CondominiumRepository condominiums;
    @Autowired BlockRepository blocks;
    @Autowired UnitRepository units;
    @Autowired ResidentRepository residents;
    @Autowired AppUserRepository users;
    @Autowired MembershipRepository memberships;
    @Autowired AuditLogRepository auditLog;
    @Autowired RetentionService retention;

    UUID condo, otherCondo, unit, residentToErase, residentToExport;

    @BeforeAll
    void seed() {
        OperatorCompany c = companies.save(new OperatorCompany("P8 Company", "p8-00000000000"));
        condo = condominiums.save(new Condominium(c.getId(), "P8 Main", null)).getId();
        otherCondo = condominiums.save(new Condominium(c.getId(), "P8 Other", null)).getId();
        Block b = blocks.save(new Block(condo, "A"));
        unit = units.save(new Unit(condo, b.getId(), "101")).getId();
        UUID unit2 = units.save(new Unit(condo, b.getId(), "102")).getId();
        residentToExport = resident("p8-export", unit);
        residentToErase = resident("p8-erase", unit2);
        staff("p8-manager", Role.CONDO_MANAGER, condo);
        staff("p8-operator", Role.OPERATOR, condo);
        staff("p8-manager-other", Role.CONDO_MANAGER, otherCondo);
    }

    private UUID resident(String subject, UUID unitId) {
        Resident r = residents.save(new Resident(condo, unitId, "Nome " + subject, subject + "@example.invalid", "+55 11 90000-0000", ResidentType.OWNER));
        AppUser u = users.save(new AppUser(subject, subject + "@example.invalid", "Nome " + subject));
        memberships.save(Membership.forResident(u.getId(), condo, r.getId()));
        return r.getId();
    }

    private void staff(String subject, Role role, UUID c) {
        AppUser u = users.save(new AppUser(subject, subject + "@example.invalid", subject));
        memberships.save(new Membership(u.getId(), role, null, c));
    }

    private static RequestPostProcessor as(String s) {
        return jwt().jwt(j -> j.subject(s));
    }

    private String url(UUID resident, String suffix) {
        return "/api/v1/condominiums/" + condo + "/residents/" + resident + suffix;
    }

    private void createInvitation(String subject) throws Exception {
        Instant now = Instant.now();
        mvc.perform(post("/api/v1/condominiums/" + condo + "/me/invitations").with(as(subject))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"visitorName\":\"Visita\",\"kind\":\"VISITOR\",\"validFrom\":\"" + now.minusSeconds(10)
                        + "\",\"validUntil\":\"" + now.plus(1, ChronoUnit.HOURS) + "\",\"maxUses\":1}"))
                .andExpect(status().isCreated());
    }

    @Test
    void exportContainsThePersonalDataAndIsRestrictedAndAudited() throws Exception {
        createInvitation("p8-export");
        mvc.perform(post("/api/v1/condominiums/" + condo + "/me/vehicles").with(as("p8-export"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"plate\":\"EXP1A23\"}")).andExpect(status().isCreated());
        UUID unitId = unit;
        mvc.perform(post("/api/v1/condominiums/" + condo + "/parcels").with(as("p8-operator")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"unitId\":\"" + unitId + "\",\"carrier\":\"Correios\"}")).andExpect(status().isCreated());

        MvcResult r = mvc.perform(get(url(residentToExport, "/export")).with(as("p8-manager"))).andExpect(status().isOk()).andReturn();
        JsonNode out = json.readTree(r.getResponse().getContentAsString());
        assertThat(out.get("resident").get("email").asString()).isEqualTo("p8-export@example.invalid");
        assertThat(out.get("account").get("email").asString()).isEqualTo("p8-export@example.invalid");
        assertThat(out.get("vehiclesOfUnit").toString()).contains("EXP1A23");
        assertThat(out.get("notifications")).isNotEmpty();
        assertThat(out.get("invitationsCreated")).hasSize(1);

        mvc.perform(get(url(residentToExport, "/export")).with(as("p8-operator"))).andExpect(status().isForbidden());
        mvc.perform(get(url(residentToExport, "/export")).with(as("p8-manager-other"))).andExpect(status().isNotFound());
        mvc.perform(get(url(residentToExport, "/export")).with(as("p8-export"))).andExpect(status().isNotFound());
        assertThat(auditLog.findByCondominiumIdOrderByOccurredAtDesc(condo)).anyMatch(e -> e.getAction().equals("resident.export"));
    }

    @Test
    void anonymizationRemovesPersonalDataRevokesAccessAndInvitations() throws Exception {
        createInvitation("p8-erase");
        mvc.perform(post(url(residentToErase, "/anonymize")).with(as("p8-operator"))).andExpect(status().isForbidden());
        mvc.perform(post(url(residentToErase, "/anonymize")).with(as("p8-manager-other"))).andExpect(status().isNotFound());
        mvc.perform(post(url(residentToErase, "/anonymize")).with(as("p8-manager"))).andExpect(status().isNoContent());

        Map<String, Object> row = jdbc.queryForMap("select full_name, email, phone, active from resident where id = ?", residentToErase);
        assertThat(row.get("full_name")).isEqualTo("ANONIMIZADO");
        assertThat(row.get("email")).isNull();
        assertThat(row.get("phone")).isNull();
        assertThat(row.get("active")).isEqualTo(false);
        Map<String, Object> user = jdbc.queryForMap("select email, display_name, active, external_subject from app_user where external_subject like 'anonymized-%' and display_name = 'ANONIMIZADO' limit 1");
        assertThat(user.get("email").toString()).endsWith("@anonymized.invalid");
        assertThat(jdbc.queryForObject("select count(*) from access_credential where status = 'ACTIVE' and created_by_user_id in (select id from app_user where display_name = 'ANONIMIZADO')", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from notification where user_id in (select id from app_user where display_name = 'ANONIMIZADO')", Integer.class)).isZero();
        // o login antigo deixa de funcionar
        mvc.perform(get("/api/v1/me/residencies").with(as("p8-erase"))).andExpect(status().isForbidden());
        assertThat(auditLog.findByCondominiumIdOrderByOccurredAtDesc(condo)).anyMatch(e -> e.getAction().equals("resident.anonymize"));
    }

    @Test
    void retentionPurgesOldDataButKeepsRecentAndReferencedRecords() {
        UUID oldUser = users.save(new AppUser("p8-ret-user", "p8-ret@example.invalid", "ret")).getId();
        jdbc.update("insert into notification (id, user_id, condominium_id, type, title, created_at) values (?, ?, ?, 'X', 'old', now() - interval '200 days')", UUID.randomUUID(), oldUser, condo);
        jdbc.update("insert into notification (id, user_id, condominium_id, type, title, created_at) values (?, ?, ?, 'X', 'recent', now() - interval '5 days')", UUID.randomUUID(), oldUser, condo);
        jdbc.update("insert into access_attempt (id, condominium_id, entrance, result, reason, occurred_at) values (?, ?, 'x', 'DENIED', 'OLD', now() - interval '800 days')", UUID.randomUUID(), condo);
        jdbc.update("insert into access_attempt (id, condominium_id, entrance, result, reason, occurred_at) values (?, ?, 'x', 'DENIED', 'RECENT', now() - interval '10 days')", UUID.randomUUID(), condo);

        UUID agent = UUID.randomUUID();
        jdbc.update("insert into agent (id, condominium_id, name, key_hash) values (?, ?, 'ret-agent', 'hash')", agent, condo);
        UUID plainOld = UUID.randomUUID();
        UUID withConflict = UUID.randomUUID();
        for (UUID e : new UUID[] {plainOld, withConflict}) {
            jdbc.update("insert into agent_event (event_id, agent_id, condominium_id, type, occurred_at, received_at) values (?, ?, ?, 'DOOR_OPENED', now() - interval '300 days', now() - interval '300 days')", e, agent, condo);
        }
        jdbc.update("insert into sync_conflict (id, event_id, condominium_id, kind, created_at) values (?, ?, ?, 'UNCERTAIN_ENTRY', now() - interval '300 days')", UUID.randomUUID(), withConflict, condo);

        Map<String, Integer> deleted = retention.purge();

        assertThat(deleted.get("notification")).isGreaterThanOrEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from notification where title = 'old'", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from notification where title = 'recent'", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from access_attempt where reason = 'OLD'", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from access_attempt where reason = 'RECENT'", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from agent_event where event_id = ?", Integer.class, plainOld)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from agent_event where event_id = ?", Integer.class, withConflict)).isEqualTo(1); // referenciado por conflito aberto
    }

    @Test
    void auditLogIsNeverPurgedByRetention() {
        long before = jdbc.queryForObject("select count(*) from audit_log", Long.class);
        retention.purge();
        assertThat(jdbc.queryForObject("select count(*) from audit_log", Long.class)).isGreaterThanOrEqualTo(before);
    }
}
