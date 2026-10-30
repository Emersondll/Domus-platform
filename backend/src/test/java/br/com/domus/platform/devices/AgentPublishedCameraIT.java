package br.com.domus.platform.devices;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.domus.platform.AbstractIntegrationTest;
import br.com.domus.platform.commercial.entity.Condominium;
import br.com.domus.platform.commercial.entity.OperatorCompany;
import br.com.domus.platform.commercial.repository.CondominiumRepository;
import br.com.domus.platform.commercial.repository.OperatorCompanyRepository;
import br.com.domus.platform.devices.adapter.MediaServer;
import br.com.domus.platform.identity.entity.AppUser;
import br.com.domus.platform.identity.entity.Membership;
import br.com.domus.platform.identity.entity.Role;
import br.com.domus.platform.identity.repository.AppUserRepository;
import br.com.domus.platform.identity.repository.MembershipRepository;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/** Câmera atrás de NAT: o agente publica o vídeo; a central não puxa nenhuma fonte e nunca guarda o endereço local. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AgentPublishedCameraIT extends AbstractIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired OperatorCompanyRepository companies;
    @Autowired CondominiumRepository condominiums;
    @Autowired AppUserRepository users;
    @Autowired MembershipRepository memberships;
    @MockitoBean MediaServer media;

    UUID condo, otherCondo;

    @BeforeAll
    void seed() {
        OperatorCompany c = companies.save(new OperatorCompany("AP Company", "ap-0000000000"));
        condo = condominiums.save(new Condominium(c.getId(), "AP Main", null)).getId();
        otherCondo = condominiums.save(new Condominium(c.getId(), "AP Other", null)).getId();
        AppUser other = users.save(new AppUser("ap-other-manager", "ap-other-manager@example.invalid", "ap-other-manager"));
        memberships.save(new Membership(other.getId(), Role.CONDO_MANAGER, null, otherCondo));
        staff("ap-manager", Role.CONDO_MANAGER);
        staff("ap-operator", Role.OPERATOR);
    }

    private void staff(String subject, Role role) {
        AppUser u = users.save(new AppUser(subject, subject + "@example.invalid", subject));
        memberships.save(new Membership(u.getId(), role, null, condo));
    }

    private static RequestPostProcessor as(String s) {
        return jwt().jwt(j -> j.subject(s));
    }

    private String register(String name, String kind, String config, int expected) throws Exception {
        return mvc.perform(post("/api/v1/condominiums/" + condo + "/devices").with(as("ap-manager")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\",\"kind\":\"" + kind + "\",\"adapter\":\"" + ("CAMERA".equals(kind) ? "ONVIF" : "SIMULATOR")
                                + "\",\"entrance\":\"P\",\"config\":" + config + "}"))
                .andExpect(status().is(expected)).andReturn().getResponse().getContentAsString();
    }

    @Test
    void anAgentPublishedCameraHasLiveVideoWithoutTheCentralPullingAnything() throws Exception {
        String body = register("Cam Agente", "CAMERA", "{\"videoSource\":\"AGENT\"}", 201);
        String id = body.replaceAll(".*\"id\":\"([^\"]+)\".*", "$1");
        org.junit.jupiter.api.Assertions.assertTrue(body.contains("\"liveVideo\":true"));
        mvc.perform(post("/api/v1/condominiums/" + condo + "/devices/" + id + "/stream-session").with(as("ap-operator")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.hlsUrl").value(org.hamcrest.Matchers.containsString("/cam-" + id + "/index.m3u8?token=")));
        verify(media, never()).ensurePath(anyString(), anyString());
    }

    @Test
    void theAgentSourceRulesAreEnforcedByTheServer() throws Exception {
        register("Cam Mista", "CAMERA", "{\"videoSource\":\"AGENT\",\"rtspUrl\":\"rtsp://localhost:8554/x\"}", 400);
        register("Cam Outra Fonte", "CAMERA", "{\"videoSource\":\"CLOUD\"}", 400);
        register("Porta Com Fonte", "ACCESS_CONTROLLER", "{\"videoSource\":\"AGENT\"}", 400);
    }

    private tools.jackson.databind.JsonNode enroll(UUID condominium, String manager, String name) throws Exception {
        String out = mvc.perform(post("/api/v1/condominiums/" + condominium + "/agents").with(as(manager))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"" + name + "\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return new tools.jackson.databind.ObjectMapper().readTree(out);
    }

    private int publish(String user, String path, String password) throws Exception {
        String body = "{\"action\":\"publish\",\"path\":\"" + path + "\",\"user\":\"" + user + "\",\"password\":\"" + password + "\"}";
        return mvc.perform(post("/internal/media/auth").contentType(MediaType.APPLICATION_JSON).content(body))
                .andReturn().getResponse().getStatus();
    }

    @Test
    void anAgentPublishesOnlyTheAgentCamerasOfItsOwnCondominiumWithItsOwnKey() throws Exception {
        var mine = enroll(condo, "ap-manager", "agente-video");
        var foreign = enroll(otherCondo, "ap-other-manager", "agente-de-outro-condominio");
        String agentUser = "agent-" + mine.get("id").asString();
        String key = mine.get("apiKey").asString();
        String cam = register("Cam Do Agente", "CAMERA", "{\"videoSource\":\"AGENT\"}", 201).replaceAll(".*\"id\":\"([^\"]+)\".*", "$1");
        String central = register("Cam Da Central", "CAMERA", "{\"rtspUrl\":\"rtsp://localhost:8554/x\"}", 201).replaceAll(".*\"id\":\"([^\"]+)\".*", "$1");

        org.junit.jupiter.api.Assertions.assertEquals(200, publish(agentUser, "cam-" + cam, key));
        org.junit.jupiter.api.Assertions.assertEquals(401, publish(agentUser, "cam-" + cam, "chave-errada"));
        org.junit.jupiter.api.Assertions.assertEquals(401, publish(agentUser, "cam-" + cam, ""));
        // a câmera que a central lê sozinha não aceita publicação de agente
        org.junit.jupiter.api.Assertions.assertEquals(401, publish(agentUser, "cam-" + central, key));
        // o agente de OUTRO condomínio, com a própria chave válida, não publica nesta câmera
        org.junit.jupiter.api.Assertions.assertEquals(401, publish("agent-" + foreign.get("id").asString(), "cam-" + cam, foreign.get("apiKey").asString()));
        org.junit.jupiter.api.Assertions.assertEquals(401, publish(agentUser, "cam-" + UUID.randomUUID(), key));
        org.junit.jupiter.api.Assertions.assertEquals(401, publish(agentUser, "nao-e-camera", key));
        org.junit.jupiter.api.Assertions.assertEquals(401, publish("agent-nao-e-uuid", "cam-" + cam, key));
        // sem o segredo único de publicação, o nome "publisher" com a chave do agente não vale
        org.junit.jupiter.api.Assertions.assertEquals(401, publish("publisher", "cam-" + cam, key));

        // revogar o agente corta a publicação
        mvc.perform(post("/api/v1/condominiums/" + condo + "/agents/" + mine.get("id").asString() + "/revoke").with(as("ap-manager")))
                .andExpect(status().isNoContent());
        org.junit.jupiter.api.Assertions.assertEquals(401, publish(agentUser, "cam-" + cam, key));
    }

    @Test
    void theStateOfAnAgentCameraIsWhetherTheAgentIsSendingVideoNow() throws Exception {
        String id = register("Cam Estado", "CAMERA", "{\"videoSource\":\"AGENT\"}", 201).replaceAll(".*\"id\":\"([^\"]+)\".*", "$1");
        String refresh = "/api/v1/condominiums/" + condo + "/devices/" + id + "/refresh-status";
        when(media.isPublishing("cam-" + id)).thenReturn(true);
        mvc.perform(post(refresh).with(as("ap-operator"))).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ONLINE"));
        when(media.isPublishing("cam-" + id)).thenReturn(false);
        mvc.perform(post(refresh).with(as("ap-operator"))).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("OFFLINE"));
        // servidor de mídia fora do ar: a central não sabe, e não inventa
        when(media.isPublishing("cam-" + id)).thenThrow(new br.com.domus.platform.devices.adapter.DeviceAdapter.DeviceCommunicationException("down"));
        mvc.perform(post(refresh).with(as("ap-operator"))).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UNKNOWN"));
    }
}
