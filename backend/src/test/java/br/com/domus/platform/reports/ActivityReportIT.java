package br.com.domus.platform.reports;

import br.com.domus.platform.AbstractIntegrationTest;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.domus.platform.commercial.entity.Block;
import br.com.domus.platform.commercial.entity.Condominium;
import br.com.domus.platform.commercial.entity.OperatorCompany;
import br.com.domus.platform.commercial.entity.Unit;
import br.com.domus.platform.commercial.repository.BlockRepository;
import br.com.domus.platform.commercial.repository.CondominiumRepository;
import br.com.domus.platform.commercial.repository.OperatorCompanyRepository;
import br.com.domus.platform.commercial.repository.UnitRepository;
import br.com.domus.platform.identity.entity.AppUser;
import br.com.domus.platform.identity.entity.Membership;
import br.com.domus.platform.identity.entity.Role;
import br.com.domus.platform.identity.repository.AppUserRepository;
import br.com.domus.platform.identity.repository.MembershipRepository;
import br.com.domus.platform.residents.entity.Resident;
import br.com.domus.platform.residents.entity.ResidentType;
import br.com.domus.platform.residents.repository.ResidentRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/** Relatório de atividade: números agregados do período, só do próprio condomínio, sem dado pessoal. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ActivityReportIT extends AbstractIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired OperatorCompanyRepository companies;
    @Autowired CondominiumRepository condominiums;
    @Autowired BlockRepository blocks;
    @Autowired UnitRepository units;
    @Autowired ResidentRepository residents;
    @Autowired AppUserRepository users;
    @Autowired MembershipRepository memberships;

    UUID condo, otherCondo, unit, operatorUser;

    @BeforeAll
    void seed() {
        OperatorCompany c = companies.save(new OperatorCompany("Rep Company", "rep-0000000000"));
        condo = condominiums.save(new Condominium(c.getId(), "Rep Main", null)).getId();
        otherCondo = condominiums.save(new Condominium(c.getId(), "Rep Other", null)).getId();
        Block b = blocks.save(new Block(condo, "A"));
        unit = units.save(new Unit(condo, b.getId(), "101")).getId();
        operatorUser = staff("rep-operator", Role.OPERATOR, condo);
        staff("rep-manager", Role.CONDO_MANAGER, condo);
        staff("rep-manager-other", Role.CONDO_MANAGER, otherCondo);
        Resident r = residents.save(new Resident(condo, unit, "Morador Rep", null, null, ResidentType.OWNER));
        AppUser u = users.save(new AppUser("rep-resident", "rep-resident@example.invalid", "res"));
        memberships.save(Membership.forResident(u.getId(), condo, r.getId()));

        Instant now = Instant.now();
        // encomendas: 2 recebidas no período (1 retirada 4 h depois), 1 antiga ainda a retirar
        parcel("RECEIVED", now.minus(2, ChronoUnit.DAYS), null);
        parcel("PICKED_UP", now.minus(1, ChronoUnit.DAYS).minus(4, ChronoUnit.HOURS), now.minus(1, ChronoUnit.DAYS));
        parcel("RECEIVED", now.minus(60, ChronoUnit.DAYS), null);
        // atendimentos: um resolvido (espera 60 s, atendimento 10 min) e um ainda na fila
        jdbc.sql("""
                INSERT INTO service_call (id, condominium_id, unit_id, source, status, assigned_user_id, created_at, assigned_at, closed_at, outcome)
                VALUES (:id, :c, :u, 'MANUAL', 'CLOSED', :op, :created, :assigned, :closed, 'RESOLVED')
                """).param("id", UUID.randomUUID()).param("c", condo).param("u", unit).param("op", operatorUser)
                .param("created", ts(now.minus(3, ChronoUnit.HOURS))).param("assigned", ts(now.minus(3, ChronoUnit.HOURS).plusSeconds(60)))
                .param("closed", ts(now.minus(3, ChronoUnit.HOURS).plusSeconds(660))).update();
        jdbc.sql("INSERT INTO service_call (id, condominium_id, unit_id, source, status, created_at) VALUES (:id, :c, :u, 'MANUAL', 'QUEUED', :created)")
                .param("id", UUID.randomUUID()).param("c", condo).param("u", unit).param("created", ts(now.minus(1, ChronoUnit.HOURS))).update();
        jdbc.sql("INSERT INTO incident (id, condominium_id, severity, title, status, opened_by, opened_at) VALUES (:id, :c, 'HIGH', 'Teste', 'OPEN', :op, :at)")
                .param("id", UUID.randomUUID()).param("c", condo).param("op", operatorUser).param("at", ts(now.minus(5, ChronoUnit.HOURS))).update();
        jdbc.sql("INSERT INTO shift_log_entry (id, condominium_id, author_user_id, kind, text, created_at) VALUES (:id, :c, :op, 'NOTE', 'x', :at)")
                .param("id", UUID.randomUUID()).param("c", condo).param("op", operatorUser).param("at", ts(now.minus(2, ChronoUnit.HOURS))).update();
        // dados de outro condomínio não entram
        jdbc.sql("INSERT INTO service_call (id, condominium_id, source, status, created_at) VALUES (:id, :c, 'MANUAL', 'QUEUED', :created)")
                .param("id", UUID.randomUUID()).param("c", otherCondo).param("created", ts(now.minus(1, ChronoUnit.HOURS))).update();
    }

    private static java.sql.Timestamp ts(Instant i) {
        return java.sql.Timestamp.from(i);
    }

    private void parcel(String status, Instant received, Instant pickedUp) {
        jdbc.sql("""
                INSERT INTO parcel (id, condominium_id, unit_id, status, received_at, received_by_user_id, picked_up_at, picked_up_by_name)
                VALUES (:id, :c, :u, :s, :r, :op, :p, :n)
                """).param("id", UUID.randomUUID()).param("c", condo).param("u", unit).param("s", status).param("r", ts(received))
                .param("op", operatorUser).param("p", pickedUp == null ? null : ts(pickedUp)).param("n", pickedUp == null ? null : "retirante").update();
    }

    private UUID staff(String subject, Role role, UUID c) {
        AppUser u = users.save(new AppUser(subject, subject + "@example.invalid", subject));
        memberships.save(new Membership(u.getId(), role, null, c));
        return u.getId();
    }

    private static RequestPostProcessor as(String s) {
        return jwt().jwt(j -> j.subject(s));
    }

    private String period() {
        Instant now = Instant.now();
        return "from=" + now.minus(7, ChronoUnit.DAYS) + "&to=" + now.plusSeconds(60);
    }

    @Test
    void aggregatesTheCondominiumActivityOfThePeriod() throws Exception {
        mvc.perform(get("/api/v1/condominiums/" + condo + "/reports/activity?" + period()).with(as("rep-manager")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.parcels.received").value(2))
                .andExpect(jsonPath("$.parcels.pickedUp").value(1))
                .andExpect(jsonPath("$.parcels.pendingNow").value(2))
                .andExpect(jsonPath("$.parcels.avgHoursToPickup").value(4.0))
                .andExpect(jsonPath("$.calls.total").value(2))
                .andExpect(jsonPath("$.calls.byOutcome.RESOLVED").value(1))
                .andExpect(jsonPath("$.calls.byOutcome.OPEN").value(1))
                .andExpect(jsonPath("$.calls.avgSecondsToAnswer").value(60.0))
                .andExpect(jsonPath("$.calls.avgMinutesHandling").value(10.0))
                .andExpect(jsonPath("$.incidents.opened").value(1))
                .andExpect(jsonPath("$.incidents.bySeverity.HIGH").value(1))
                .andExpect(jsonPath("$.shiftLog.notes").value(1));
        mvc.perform(get("/api/v1/condominiums/" + condo + "/reports/activity?" + period()).with(as("rep-operator")))
                .andExpect(status().isOk());
    }

    @Test
    void csvHasTheSameNumbersAndNoPersonalData() throws Exception {
        mvc.perform(get("/api/v1/condominiums/" + condo + "/reports/activity.csv?" + period()).with(as("rep-manager")))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("encomendas,recebidas,2")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("atendimentos,desfecho_RESOLVED,1")))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("retirante"))));
    }

    @Test
    void isScopedToTheCondominiumAndTheAudience() throws Exception {
        String url = "/api/v1/condominiums/" + condo + "/reports/activity?" + period();
        mvc.perform(get(url).with(as("rep-manager-other"))).andExpect(status().isNotFound());
        mvc.perform(get(url).with(as("rep-resident"))).andExpect(status().isNotFound());
        mvc.perform(get(url)).andExpect(status().isUnauthorized());
        Instant now = Instant.now();
        mvc.perform(get("/api/v1/condominiums/" + condo + "/reports/activity?from=" + now.minus(40, ChronoUnit.DAYS) + "&to=" + now)
                .with(as("rep-manager"))).andExpect(status().isBadRequest());
    }

    @Test
    void byUnitIsForTheManagerOnlyAndHasNoPersonalNames() throws Exception {
        String url = "/api/v1/condominiums/" + condo + "/reports/activity/by-unit?" + period();
        mvc.perform(get(url).with(as("rep-manager"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].label").value("A / 101"))
                .andExpect(jsonPath("$[0].parcelsReceived").value(2))
                .andExpect(jsonPath("$[0].calls").value(2))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("Morador Rep"))));
        // o operador vê o resumo do condomínio, mas não a rotina por unidade
        mvc.perform(get(url).with(as("rep-operator"))).andExpect(status().isForbidden());
        mvc.perform(get(url).with(as("rep-resident"))).andExpect(status().isNotFound());
        mvc.perform(get(url).with(as("rep-manager-other"))).andExpect(status().isNotFound());
        mvc.perform(get(url.replace("/by-unit?", "/by-unit.csv?")).with(as("rep-manager"))).andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("\"A / 101\",2,2")));
        mvc.perform(get("/api/v1/condominiums/" + condo + "/reports/activity/by-unit?from=" + Instant.now().minus(60, ChronoUnit.DAYS)
                + "&to=" + Instant.now()).with(as("rep-manager"))).andExpect(status().isBadRequest());
        Integer audited = jdbc.sql("SELECT count(*) FROM audit_log WHERE action = 'report.activity.by-unit' AND outcome = 'SUCCESS' AND condominium_id = :c")
                .param("c", condo).query(Integer.class).single();
        org.junit.jupiter.api.Assertions.assertTrue(audited >= 1, "a consulta por unidade deve ser auditada");
    }
}
