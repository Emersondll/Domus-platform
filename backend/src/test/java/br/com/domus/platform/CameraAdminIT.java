package br.com.domus.platform;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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
import java.util.UUID;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/** Edição, desativação e papel de câmera de entrada de visitas. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CameraAdminIT extends AbstractIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired OperatorCompanyRepository companies;
    @Autowired CondominiumRepository condominiums;
    @Autowired AppUserRepository users;
    @Autowired MembershipRepository memberships;

    UUID condo, otherCondo;

    @BeforeAll
    void seed() {
        OperatorCompany c = companies.save(new OperatorCompany("CA Company", "ca-00000000000"));
        condo = condominiums.save(new Condominium(c.getId(), "CA Main", null)).getId();
        otherCondo = condominiums.save(new Condominium(c.getId(), "CA Other", null)).getId();
        member("ca-manager", Role.CONDO_MANAGER, condo);
        member("ca-operator", Role.OPERATOR, condo);
        member("ca-other-manager", Role.CONDO_MANAGER, otherCondo);
    }

    private void member(String subject, Role role, UUID condominium) {
        AppUser u = users.save(new AppUser(subject, subject + "@example.invalid", subject));
        memberships.save(new Membership(u.getId(), role, null, condominium));
    }

    private static RequestPostProcessor as(String s) {
        return jwt().jwt(j -> j.subject(s));
    }

    private String devices() {
        return "/api/v1/condominiums/" + condo + "/devices";
    }

    private String register(String name, String entrance, String kind, String config) throws Exception {
        String adapter = "CAMERA".equals(kind) ? "ONVIF" : "SIMULATOR";
        String res = mvc.perform(post(devices()).with(as("ca-manager")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\",\"kind\":\"" + kind + "\",\"adapter\":\"" + adapter
                                + "\",\"entrance\":\"" + entrance + "\",\"config\":" + config + "}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return json.readTree(res).get("id").asText();
    }

    private org.springframework.test.web.servlet.ResultActions send(String url, String who, String body) throws Exception {
        return mvc.perform(post(url).with(as(who)).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    @Test
    void managerEditsACameraAndOthersCannot() throws Exception {
        String id = register("Edit Cam", "portaria", "CAMERA", "{\"rtspUrl\":\"rtsp://localhost:8554/a\"}");
        register("Other Cam", "portaria", "CAMERA", "{}");
        String body = "{\"name\":\"Cam Hall\",\"entrance\":\"hall\",\"config\":{\"rtspUrl\":\"rtsp://127.0.0.1:8554/hall\"}}";
        mvc.perform(put(devices() + "/" + id).with(as("ca-operator")).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
        mvc.perform(put("/api/v1/condominiums/" + condo + "/devices/" + id).with(as("ca-other-manager"))
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isNotFound());
        mvc.perform(put(devices() + "/" + id).with(as("ca-manager")).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("$.name").value("Cam Hall"))
                .andExpect(jsonPath("$.entrance").value("hall"))
                .andExpect(jsonPath("$.config.rtspUrl").value("rtsp://127.0.0.1:8554/hall"));
        mvc.perform(put(devices() + "/" + id).with(as("ca-manager")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Other Cam\",\"entrance\":\"hall\",\"config\":{}}")).andExpect(status().isConflict());
        mvc.perform(put(devices() + "/" + id).with(as("ca-manager")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Cam Hall\",\"entrance\":\"hall\",\"config\":{\"rtspUrl\":\"rtsp://user:pw@localhost/x\"}}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void deactivatedCameraHasNoVideoAndLosesTheEntranceRole() throws Exception {
        String id = register("Role Cam", "garagem", "CAMERA", "{\"rtspUrl\":\"rtsp://localhost:8554/g\"}");
        send(devices() + "/" + id + "/visitor-entrance", "ca-manager", "{\"enabled\":true}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.visitorEntrance").value(true));
        send(devices() + "/" + id + "/active", "ca-manager", "{\"active\":false}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.active").value(false))
                .andExpect(jsonPath("$.visitorEntrance").value(false));
        send(devices() + "/" + id + "/stream-session", "ca-operator", "{}").andExpect(status().isConflict());
        send(devices() + "/" + id + "/visitor-entrance", "ca-manager", "{\"enabled\":true}").andExpect(status().isConflict());
        send(devices() + "/" + id + "/active", "ca-operator", "{\"active\":true}").andExpect(status().isForbidden());
        send(devices() + "/" + id + "/active", "ca-manager", "{\"active\":true}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.active").value(true));
    }

    @Test
    void onlyOneVisitorEntranceCameraPerEntranceAndOnlyCameras() throws Exception {
        String a = register("Ent A", "social", "CAMERA", "{}");
        String b = register("Ent B", "social", "CAMERA", "{}");
        String c = register("Ent C", "servico", "CAMERA", "{}");
        String door = register("Portao X", "social", "ACCESS_CONTROLLER", "{}");
        send(devices() + "/" + a + "/visitor-entrance", "ca-manager", "{\"enabled\":true}").andExpect(status().isOk());
        send(devices() + "/" + c + "/visitor-entrance", "ca-manager", "{\"enabled\":true}").andExpect(status().isOk());
        send(devices() + "/" + b + "/visitor-entrance", "ca-manager", "{\"enabled\":true}").andExpect(status().isOk());
        send(devices() + "/" + door + "/visitor-entrance", "ca-manager", "{\"enabled\":true}").andExpect(status().isConflict());
        send(devices() + "/" + door + "/active", "ca-manager", "{\"active\":false}").andExpect(status().isConflict());
        String list = mvc.perform(get(devices() + "?size=100").with(as("ca-operator")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        var items = json.readTree(list).get("items");
        java.util.Map<String, Boolean> flag = new java.util.HashMap<>();
        items.forEach(n -> flag.put(n.get("id").asText(), n.get("visitorEntrance").asBoolean()));
        org.junit.jupiter.api.Assertions.assertFalse(flag.get(a)); // trocada por B na mesma entrada
        org.junit.jupiter.api.Assertions.assertTrue(flag.get(b));
        org.junit.jupiter.api.Assertions.assertTrue(flag.get(c)); // outra entrada, convive
        // com a câmera marcada, mudar a entrada exige desmarcar antes
        mvc.perform(put(devices() + "/" + b).with(as("ca-manager")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Ent B\",\"entrance\":\"outra\",\"config\":{}}")).andExpect(status().isConflict());
    }
}
