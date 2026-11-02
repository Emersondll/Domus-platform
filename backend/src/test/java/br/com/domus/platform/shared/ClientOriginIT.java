package br.com.domus.platform.shared;

import br.com.domus.platform.AbstractIntegrationTest;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.domus.platform.commercial.entity.Condominium;
import br.com.domus.platform.commercial.entity.OperatorCompany;
import br.com.domus.platform.commercial.repository.CondominiumRepository;
import br.com.domus.platform.commercial.repository.OperatorCompanyRepository;
import br.com.domus.platform.identity.entity.AppUser;
import br.com.domus.platform.identity.entity.Membership;
import br.com.domus.platform.identity.entity.Role;
import br.com.domus.platform.identity.repository.AppUserRepository;
import br.com.domus.platform.identity.repository.MembershipRepository;
import br.com.domus.platform.shared.audit.AuditOutcome;
import br.com.domus.platform.shared.audit.AuditService;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/** A auditoria grava de onde veio a ação (canal e tipo de cliente), sem IP, e trata fora de requisição como do sistema. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ClientOriginIT extends AbstractIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired AuditService audit;
    @Autowired OperatorCompanyRepository companies;
    @Autowired CondominiumRepository condominiums;
    @Autowired AppUserRepository users;
    @Autowired MembershipRepository memberships;

    UUID condo;

    @BeforeAll
    void seed() {
        OperatorCompany c = companies.save(new OperatorCompany("Origin Company", "origin-0000001"));
        condo = condominiums.save(new Condominium(c.getId(), "Origin Main", null)).getId();
        AppUser u = users.save(new AppUser("origin-manager", "origin-manager@example.invalid", "origin-manager"));
        memberships.save(new Membership(u.getId(), Role.CONDO_MANAGER, null, condo));
    }

    private static RequestPostProcessor as(String s) {
        return jwt().jwt(j -> j.subject(s));
    }

    private Map<String, Object> publish(String title, String client, String userAgent) throws Exception {
        var req = post("/api/v1/condominiums/" + condo + "/announcements").with(as("origin-manager"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"" + title + "\",\"body\":\"texto\"}");
        if (client != null) req = req.header("X-Domus-Client", client);
        if (userAgent != null) req = req.header("User-Agent", userAgent);
        mvc.perform(req).andExpect(status().isCreated());
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select client_origin, client_kind from audit_log where condominium_id = ? and action = 'announcement.publish' "
                        + "order by occurred_at desc limit 1", condo);
        return rows.get(0);
    }

    @Test
    void thePanelAndTheAppAreRecordedAsDeclaredAndAnythingElseIsApi() throws Exception {
        assertThat(publish("a", "PANEL", "Mozilla/5.0 (X11; Linux)")).containsEntry("client_origin", "PANEL")
                .containsEntry("client_kind", "BROWSER");
        assertThat(publish("b", "RESIDENT_APP", "Mozilla/5.0 (iPhone)")).containsEntry("client_origin", "RESIDENT_APP")
                .containsEntry("client_kind", "BROWSER");
        // chamada manual: sem cabeçalho e com cliente de linha de comando
        assertThat(publish("c", null, "curl/8.5.0")).containsEntry("client_origin", "API").containsEntry("client_kind", "OTHER");
        // valor desconhecido no cabeçalho não vira canal novo
        assertThat(publish("d", "HACKER", "python-requests/2.31")).containsEntry("client_origin", "API")
                .containsEntry("client_kind", "OTHER");
    }

    @Test
    void nothingIsStoredThatIdentifiesTheCaller() throws Exception {
        publish("e", "PANEL", "Mozilla/5.0 (X11; Linux) Chrome/130 secret-build-id-123");
        // só as colunas de canal e tipo existem para a origem: o user-agent inteiro e o IP não são gravados
        List<String> columns = jdbc.queryForList("select column_name from information_schema.columns where table_name = 'audit_log'",
                String.class);
        assertThat(columns).contains("client_origin", "client_kind").noneMatch(c -> c.contains("ip") || c.contains("agent"));
        Integer leaked = jdbc.queryForObject("select count(*) from audit_log where detail like '%secret-build-id%' "
                + "or client_origin like '%secret%' or client_kind like '%secret%'", Integer.class);
        assertThat(leaked).isZero();
    }

    @Test
    void anAuditFactOutsideAnyRequestBelongsToTheSystem() {
        String id = UUID.randomUUID().toString();
        audit.record(null, null, condo, "test.system-fact", "test", id, AuditOutcome.SUCCESS);
        Map<String, Object> row = jdbc.queryForMap("select client_origin, client_kind from audit_log where resource_id = ?", id);
        assertThat(row).containsEntry("client_origin", "SYSTEM").containsEntry("client_kind", null);
    }
}
