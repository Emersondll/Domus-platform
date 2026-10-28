package br.com.domus.platform.access;

import br.com.domus.platform.AbstractIntegrationTest;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
import br.com.domus.platform.access.repository.VisitSnapshotRepository;
import br.com.domus.platform.privacy.service.RetentionService;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import tools.jackson.databind.ObjectMapper;

/** Imagem da câmera de entrada enviada junto do pedido de visita (câmera simulada). */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class VisitSnapshotIT extends AbstractIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired OperatorCompanyRepository companies;
    @Autowired CondominiumRepository condominiums;
    @Autowired BlockRepository blocks;
    @Autowired UnitRepository units;
    @Autowired ResidentRepository residents;
    @Autowired AppUserRepository users;
    @Autowired MembershipRepository memberships;
    @Autowired VisitSnapshotRepository snapshots;
    @Autowired RetentionService retention;
    @Autowired org.springframework.jdbc.core.simple.JdbcClient jdbc;

    UUID condo, otherCondo, unit1, unit2;
    String cameraId;

    @BeforeAll
    void seed() throws Exception {
        OperatorCompany c = companies.save(new OperatorCompany("VS Company", "vs-00000000000"));
        condo = condominiums.save(new Condominium(c.getId(), "VS Main", null)).getId();
        otherCondo = condominiums.save(new Condominium(c.getId(), "VS Other", null)).getId();
        Block b = blocks.save(new Block(condo, "D"));
        unit1 = units.save(new Unit(condo, b.getId(), "21")).getId();
        unit2 = units.save(new Unit(condo, b.getId(), "22")).getId();
        resident("vs-res", unit1);
        resident("vs-res-other-unit", unit2);
        member("vs-manager", Role.CONDO_MANAGER, condo);
        member("vs-operator", Role.OPERATOR, condo);
        member("vs-operator2", Role.OPERATOR, condo);
        member("vs-other-operator", Role.OPERATOR, otherCondo);
        String res = mvc.perform(post(base() + "/devices").with(as("vs-manager")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Camera Entrada\",\"kind\":\"CAMERA\",\"adapter\":\"SIMULATOR\",\"entrance\":\"social\",\"config\":{}}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        cameraId = json.readTree(res).get("id").asText();
        mvc.perform(post(base() + "/devices/" + cameraId + "/visitor-entrance").with(as("vs-manager"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":true}")).andExpect(status().isOk());
    }

    private void resident(String subject, UUID unit) {
        Resident r = residents.save(new Resident(condo, unit, subject, null, null, ResidentType.OWNER));
        AppUser u = users.save(new AppUser(subject, subject + "@example.invalid", subject));
        memberships.save(Membership.forResident(u.getId(), condo, r.getId()));
    }

    private void member(String subject, Role role, UUID condominium) {
        AppUser u = users.save(new AppUser(subject, subject + "@example.invalid", subject));
        memberships.save(new Membership(u.getId(), role, null, condominium));
    }

    private static RequestPostProcessor as(String s) {
        return jwt().jwt(j -> j.subject(s));
    }

    private String base() {
        return "/api/v1/condominiums/" + condo;
    }

    private String capture(String who) throws Exception {
        String res = mvc.perform(post(base() + "/entrance-snapshots").with(as(who)).contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.cameraName").value("Camera Entrada"))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(res).get("id").asText();
    }

    private String request(String who, UUID unit, String snapshotId) throws Exception {
        String body = "{\"unitId\":\"" + unit + "\",\"visitorName\":\"Moço da entrega\",\"kind\":\"VISITOR\""
                + (snapshotId == null ? "" : ",\"snapshotId\":\"" + snapshotId + "\"") + "}";
        String res = mvc.perform(post(base() + "/visit-requests").with(as(who)).contentType(MediaType.APPLICATION_JSON)
                .content(body)).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return json.readTree(res).get("id").asText();
    }

    @Test
    void operatorCapturesPreviewsAndSendsAndOnlyTheUnitResidentSeesIt() throws Exception {
        mvc.perform(get(base() + "/entrance-snapshots/availability").with(as("vs-operator")))
                .andExpect(jsonPath("$.enabled").value(true)).andExpect(jsonPath("$.entrances[0]").value("social"));
        String snap = capture("vs-operator");

        // prévia só para quem capturou
        byte[] preview = mvc.perform(get(base() + "/entrance-snapshots/" + snap + "/image").with(as("vs-operator")))
                .andExpect(status().isOk()).andExpect(content().contentType(MediaType.IMAGE_JPEG))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
                        .string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")))
                .andReturn().getResponse().getContentAsByteArray();
        org.junit.jupiter.api.Assertions.assertTrue(preview.length > 1000 && preview.length <= 200 * 1024);
        org.junit.jupiter.api.Assertions.assertEquals((byte) 0xFF, preview[0]); // assinatura JPEG
        mvc.perform(get(base() + "/entrance-snapshots/" + snap + "/image").with(as("vs-operator2"))).andExpect(status().isNotFound());
        mvc.perform(get(base() + "/entrance-snapshots/" + snap + "/image").with(as("vs-res"))).andExpect(status().isNotFound());

        // outro operador não consegue enviar a imagem alheia
        mvc.perform(post(base() + "/visit-requests").with(as("vs-operator2")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"unitId\":\"" + unit1 + "\",\"visitorName\":\"X\",\"kind\":\"VISITOR\",\"snapshotId\":\"" + snap + "\"}"))
                .andExpect(status().isNotFound());

        String req = request("vs-operator", unit1, snap);
        mvc.perform(get(base() + "/visit-requests").with(as("vs-operator")))
                .andExpect(jsonPath("$.items[?(@.id=='" + req + "')].hasSnapshot").value(true));
        mvc.perform(get(base() + "/me/notifications").with(as("vs-res")))
                .andExpect(jsonPath("$.items[?(@.title=='Visita na portaria')].body").value(org.hamcrest.Matchers.hasItem(
                        org.hamcrest.Matchers.containsString("com imagem da entrada"))));

        // morador da unidade vê; a lista dele mostra hasSnapshot
        mvc.perform(get(base() + "/me/visit-requests").with(as("vs-res")))
                .andExpect(jsonPath("$.items[?(@.id=='" + req + "')].hasSnapshot").value(true));
        byte[] seen = mvc.perform(get(base() + "/me/visit-requests/" + req + "/snapshot").with(as("vs-res")))
                .andExpect(status().isOk()).andExpect(content().contentType(MediaType.IMAGE_JPEG))
                .andReturn().getResponse().getContentAsByteArray();
        org.junit.jupiter.api.Assertions.assertArrayEquals(preview, seen);
        // morador de outra unidade, operador e pessoa sem token
        mvc.perform(get(base() + "/me/visit-requests/" + req + "/snapshot").with(as("vs-res-other-unit")))
                .andExpect(status().isNotFound());
        mvc.perform(get(base() + "/me/visit-requests/" + req + "/snapshot").with(as("vs-operator")))
                .andExpect(status().isNotFound());
        mvc.perform(get(base() + "/me/visit-requests/" + req + "/snapshot")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/condominiums/" + otherCondo + "/visit-requests/" + req + "/snapshot")
                .with(as("vs-other-operator"))).andExpect(status().isNotFound());
        // a portaria também vê
        mvc.perform(get(base() + "/visit-requests/" + req + "/snapshot").with(as("vs-operator2"))).andExpect(status().isOk());

        // a imagem não pode ir em dois pedidos
        mvc.perform(post(base() + "/visit-requests").with(as("vs-operator")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"unitId\":\"" + unit1 + "\",\"visitorName\":\"Y\",\"kind\":\"VISITOR\",\"snapshotId\":\"" + snap + "\"}"))
                .andExpect(status().isConflict());

        // conteúdo cifrado em repouso: o campo do banco não contém o JPEG em base64 legível
        String stored = jdbc.sql("SELECT content FROM visit_snapshot WHERE id = :id").param("id", UUID.fromString(snap))
                .query(String.class).single();
        org.junit.jupiter.api.Assertions.assertTrue(stored.startsWith("enc:v1:"));
        org.junit.jupiter.api.Assertions.assertFalse(stored.contains("/9j/")); // início do JPEG em base64
    }

    @Test
    void requestWithoutImageStillWorksAndHasNone() throws Exception {
        String req = request("vs-operator", unit1, null);
        mvc.perform(get(base() + "/me/visit-requests/" + req + "/snapshot").with(as("vs-res"))).andExpect(status().isNotFound());
        mvc.perform(get(base() + "/visit-requests").with(as("vs-operator")))
                .andExpect(jsonPath("$.items[?(@.id=='" + req + "')].hasSnapshot").value(false));
    }

    @Test
    void residentsCannotCaptureAndCameraProblemsAreExplicit() throws Exception {
        mvc.perform(post(base() + "/entrance-snapshots").with(as("vs-res")).contentType(MediaType.APPLICATION_JSON)
                .content("{}")).andExpect(status().isNotFound());
        mvc.perform(post(base() + "/entrance-snapshots").with(as("vs-other-operator")).contentType(MediaType.APPLICATION_JSON)
                .content("{}")).andExpect(status().isNotFound());
        mvc.perform(post(base() + "/entrance-snapshots").with(as("vs-operator")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"entrance\":\"inexistente\"}")).andExpect(status().isConflict());
        // sem câmera de entrada marcada em um condomínio novo: 409 claro
        mvc.perform(post("/api/v1/condominiums/" + otherCondo + "/entrance-snapshots").with(as("vs-other-operator"))
                .contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isConflict());
    }

    @Test
    void retentionDropsExpiredLooseImagesAndOldSentOnes() throws Exception {
        String loose = capture("vs-operator");
        String sent = capture("vs-operator");
        request("vs-operator", unit1, sent);
        jdbc.sql("UPDATE visit_snapshot SET expires_at = :t WHERE id = :id").param("t", java.sql.Timestamp.from(Instant.now().minusSeconds(60)))
                .param("id", UUID.fromString(loose)).update();
        retention.purge();
        org.junit.jupiter.api.Assertions.assertTrue(snapshots.findById(UUID.fromString(loose)).isEmpty());
        org.junit.jupiter.api.Assertions.assertTrue(snapshots.findById(UUID.fromString(sent)).isPresent()); // enviada: dentro do prazo
        jdbc.sql("UPDATE visit_snapshot SET captured_at = :t WHERE id = :id")
                .param("t", java.sql.Timestamp.from(Instant.now().minus(java.time.Duration.ofDays(31))))
                .param("id", UUID.fromString(sent)).update();
        retention.purge();
        org.junit.jupiter.api.Assertions.assertTrue(snapshots.findById(UUID.fromString(sent)).isEmpty());
    }
}
