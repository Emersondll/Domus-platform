package br.com.domus.platform.devices;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.domus.platform.AbstractIntegrationTest;
import br.com.domus.platform.commercial.entity.Block;
import br.com.domus.platform.commercial.entity.Condominium;
import br.com.domus.platform.commercial.entity.OperatorCompany;
import br.com.domus.platform.commercial.entity.Unit;
import br.com.domus.platform.commercial.repository.BlockRepository;
import br.com.domus.platform.commercial.repository.CondominiumRepository;
import br.com.domus.platform.commercial.repository.OperatorCompanyRepository;
import br.com.domus.platform.commercial.repository.UnitRepository;
import br.com.domus.platform.devices.adapter.MediaServer;
import br.com.domus.platform.identity.entity.AppUser;
import br.com.domus.platform.identity.entity.Membership;
import br.com.domus.platform.identity.entity.Role;
import br.com.domus.platform.identity.repository.AppUserRepository;
import br.com.domus.platform.identity.repository.MembershipRepository;
import br.com.domus.platform.residents.entity.Resident;
import br.com.domus.platform.residents.entity.ResidentType;
import br.com.domus.platform.residents.repository.ResidentRepository;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/** CR5: usuário e senha da câmera cadastrados pela tela ficam cifrados, nunca voltam pela API e chegam só ao servidor de mídia. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DeviceCredentialIT extends AbstractIntegrationTest {

    static final String USER = "cam user";
    static final String PASSWORD = "P@ss:w/rd-Zq91";

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired OperatorCompanyRepository companies;
    @Autowired CondominiumRepository condominiums;
    @Autowired BlockRepository blocks;
    @Autowired UnitRepository units;
    @Autowired ResidentRepository residents;
    @Autowired AppUserRepository users;
    @Autowired MembershipRepository memberships;
    @MockitoBean MediaServer media;

    UUID condo, otherCondo;

    @BeforeAll
    void seed() {
        OperatorCompany c = companies.save(new OperatorCompany("DC Company", "dc-0000000000"));
        condo = condominiums.save(new Condominium(c.getId(), "DC Main", null)).getId();
        otherCondo = condominiums.save(new Condominium(c.getId(), "DC Other", null)).getId();
        Block b = blocks.save(new Block(condo, "A"));
        UUID unit = units.save(new Unit(condo, b.getId(), "101")).getId();
        Resident r = residents.save(new Resident(condo, unit, "dc-res", null, null, ResidentType.OWNER));
        AppUser res = users.save(new AppUser("dc-res", "dc-res@example.invalid", "res"));
        memberships.save(Membership.forResident(res.getId(), condo, r.getId()));
        staff("dc-manager", Role.CONDO_MANAGER, condo);
        staff("dc-operator", Role.OPERATOR, condo);
        staff("dc-other-manager", Role.CONDO_MANAGER, otherCondo);
    }

    private void staff(String subject, Role role, UUID condominium) {
        AppUser u = users.save(new AppUser(subject, subject + "@example.invalid", subject));
        memberships.save(new Membership(u.getId(), role, null, condominium));
    }

    private static RequestPostProcessor as(String s) {
        return jwt().jwt(j -> j.subject(s));
    }

    private String devices() {
        return "/api/v1/condominiums/" + condo + "/devices";
    }

    private String camera(String name) throws Exception {
        String res = mvc.perform(post(devices()).with(as("dc-manager")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\",\"kind\":\"CAMERA\",\"adapter\":\"ONVIF\",\"entrance\":\"P\","
                                + "\"config\":{\"rtspUrl\":\"rtsp://localhost:8554/dc\"}}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return res.replaceAll(".*\"id\":\"([^\"]+)\".*", "$1");
    }

    private static String body(String user, String password) {
        return "{\"username\":\"" + user + "\",\"password\":\"" + password + "\"}";
    }

    @Test
    void theManagerStoresAndRotatesTheCredentialAndTheApiNeverReturnsIt() throws Exception {
        String id = camera("Cam Credencial");
        String url = devices() + "/" + id + "/credentials";
        mvc.perform(get(url).with(as("dc-manager"))).andExpect(status().isOk()).andExpect(jsonPath("$.configured").value(false));
        String put = mvc.perform(put(url).with(as("dc-manager")).contentType(MediaType.APPLICATION_JSON).content(body(USER, PASSWORD)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.configured").value(true))
                .andReturn().getResponse().getContentAsString();
        assertFalse(put.contains(PASSWORD) || put.contains(USER));
        String get = mvc.perform(get(url).with(as("dc-manager"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.configured").value(true)).andExpect(jsonPath("$.updatedAt").exists())
                .andReturn().getResponse().getContentAsString();
        assertFalse(get.contains(PASSWORD) || get.contains(USER));
        // nem a listagem nem o detalhe do dispositivo carregam o segredo
        String list = mvc.perform(get(devices()).with(as("dc-manager"))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertFalse(list.contains(PASSWORD) || list.contains("p%40ss"));
        // rotação: a segunda gravação substitui a primeira
        mvc.perform(put(url).with(as("dc-manager")).contentType(MediaType.APPLICATION_JSON).content(body("novo", "Outra-Senha-77")))
                .andExpect(status().isOk());
        Integer rows = jdbc.sql("SELECT count(*) FROM device_credential WHERE device_id = :d").param("d", UUID.fromString(id))
                .query(Integer.class).single();
        org.junit.jupiter.api.Assertions.assertEquals(1, rows);
    }

    @Test
    void theDatabaseHoldsOnlyCiphertextAndTheAuditNeverHasTheValue() throws Exception {
        String id = camera("Cam Cifrada");
        mvc.perform(put(devices() + "/" + id + "/credentials").with(as("dc-manager")).contentType(MediaType.APPLICATION_JSON)
                .content(body(USER, PASSWORD))).andExpect(status().isOk());
        String stored = jdbc.sql("SELECT username_enc || '|' || password_enc FROM device_credential WHERE device_id = :d")
                .param("d", UUID.fromString(id)).query(String.class).single();
        assertFalse(stored.contains(PASSWORD) || stored.contains("Zq91") || stored.contains(USER), "o banco não pode ter o texto claro");
        Integer audit = jdbc.sql("SELECT count(*) FROM audit_log WHERE action = 'device.credentials.set' AND outcome = 'SUCCESS' AND resource_id = :r")
                .param("r", id).query(Integer.class).single();
        assertTrue(audit >= 1);
        Integer leaked = jdbc.sql("SELECT count(*) FROM audit_log WHERE CAST(audit_log.* AS text) LIKE :p").param("p", "%Zq91%")
                .query(Integer.class).single();
        org.junit.jupiter.api.Assertions.assertEquals(0, leaked);
    }

    @Test
    void onlyTheManagerOfTheCondominiumMaySetOrReadTheStatus() throws Exception {
        String id = camera("Cam Permissao");
        String url = devices() + "/" + id + "/credentials";
        mvc.perform(put(url).with(as("dc-operator")).contentType(MediaType.APPLICATION_JSON).content(body(USER, PASSWORD)))
                .andExpect(status().isForbidden());
        mvc.perform(get(url).with(as("dc-operator"))).andExpect(status().isForbidden());
        mvc.perform(put(url).with(as("dc-res")).contentType(MediaType.APPLICATION_JSON).content(body(USER, PASSWORD)))
                .andExpect(status().isNotFound());
        mvc.perform(put(url).with(as("dc-other-manager")).contentType(MediaType.APPLICATION_JSON).content(body(USER, PASSWORD)))
                .andExpect(status().isNotFound());
        mvc.perform(delete(url).with(as("dc-other-manager"))).andExpect(status().isNotFound());
        mvc.perform(put(url).contentType(MediaType.APPLICATION_JSON).content(body(USER, PASSWORD))).andExpect(status().isUnauthorized());
        // câmera de outro condomínio pelo caminho de um condomínio onde a pessoa gerencia
        mvc.perform(put("/api/v1/condominiums/" + otherCondo + "/devices/" + id + "/credentials").with(as("dc-other-manager"))
                .contentType(MediaType.APPLICATION_JSON).content(body(USER, PASSWORD))).andExpect(status().isNotFound());
    }

    @Test
    void theStoredCredentialReachesOnlyTheMediaServerSourceAndRemovingItDropsIt() throws Exception {
        String id = camera("Cam Fonte");
        mvc.perform(put(devices() + "/" + id + "/credentials").with(as("dc-manager")).contentType(MediaType.APPLICATION_JSON)
                .content(body(USER, PASSWORD))).andExpect(status().isOk());
        String session = mvc.perform(post(devices() + "/" + id + "/stream-session").with(as("dc-operator")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertFalse(session.contains("P%40ss") || session.contains("rtsp://"), "a resposta ao navegador não carrega a fonte RTSP");
        // usuário e senha entram escapados na parte de autoridade da URL entregue ao servidor de mídia
        verify(media).ensurePath(eq("cam-" + id), startsWith("rtsp://cam%20user:P%40ss%3Aw%2Frd-Zq91@localhost:8554/dc"));
        Mockito.clearInvocations(media);
        mvc.perform(delete(devices() + "/" + id + "/credentials").with(as("dc-manager"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.configured").value(false));
        mvc.perform(post(devices() + "/" + id + "/stream-session").with(as("dc-operator"))).andExpect(status().isOk());
        verify(media).ensurePath(eq("cam-" + id), eq("rtsp://localhost:8554/dc"));
    }

    @Test
    void invalidPayloadsAndNonCamerasAreRefused() throws Exception {
        String id = camera("Cam Validacao");
        String url = devices() + "/" + id + "/credentials";
        mvc.perform(put(url).with(as("dc-manager")).contentType(MediaType.APPLICATION_JSON).content(body("", "x")))
                .andExpect(status().isBadRequest());
        mvc.perform(put(url).with(as("dc-manager")).contentType(MediaType.APPLICATION_JSON).content(body("u", "")))
                .andExpect(status().isBadRequest());
        mvc.perform(put(url).with(as("dc-manager")).contentType(MediaType.APPLICATION_JSON).content(body("u", "a\\nb")))
                .andExpect(status().isBadRequest());
        mvc.perform(put(url).with(as("dc-manager")).contentType(MediaType.APPLICATION_JSON).content(body("u", "x".repeat(129))))
                .andExpect(status().isBadRequest());
        String door = mvc.perform(post(devices()).with(as("dc-manager")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Porta DC\",\"kind\":\"ACCESS_CONTROLLER\",\"adapter\":\"SIMULATOR\",\"entrance\":\"P\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()
                .replaceAll(".*\"id\":\"([^\"]+)\".*", "$1");
        mvc.perform(put(devices() + "/" + door + "/credentials").with(as("dc-manager")).contentType(MediaType.APPLICATION_JSON)
                .content(body(USER, PASSWORD))).andExpect(status().isConflict());
    }

    @Test
    void secretsAreRefusedInsideThePlainDeviceConfig() throws Exception {
        for (String cfg : new String[]{"{\"password\":\"x\"}", "{\"apiKey\":\"x\"}", "{\"authToken\":\"x\"}", "{\"secret\":\"x\"}"}) {
            mvc.perform(post(devices()).with(as("dc-manager")).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"name\":\"Cfg " + cfg.hashCode() + "\",\"kind\":\"CAMERA\",\"adapter\":\"ONVIF\",\"entrance\":\"P\",\"config\":" + cfg + "}"))
                    .andExpect(status().isBadRequest());
        }
        // nomes de variáveis de ambiente (terminam em Env) continuam permitidos
        mvc.perform(post(devices()).with(as("dc-manager")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Cfg Env\",\"kind\":\"CAMERA\",\"adapter\":\"ONVIF\",\"entrance\":\"P\","
                                + "\"config\":{\"rtspCredentialsEnv\":\"CAM_PASS\"}}"))
                .andExpect(status().isCreated());
    }
}
