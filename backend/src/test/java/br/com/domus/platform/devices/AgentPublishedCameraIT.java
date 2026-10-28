package br.com.domus.platform.devices;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
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

    UUID condo;

    @BeforeAll
    void seed() {
        OperatorCompany c = companies.save(new OperatorCompany("AP Company", "ap-0000000000"));
        condo = condominiums.save(new Condominium(c.getId(), "AP Main", null)).getId();
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
}
