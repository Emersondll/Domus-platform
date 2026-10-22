package br.com.domus.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.domus.platform.commercial.entity.Condominium;
import br.com.domus.platform.commercial.repository.CondominiumRepository;
import br.com.domus.platform.commercial.entity.OperatorCompany;
import br.com.domus.platform.commercial.repository.OperatorCompanyRepository;
import br.com.domus.platform.devices.service.AccessCommandService;
import br.com.domus.platform.devices.service.DahuaEventSubscriber;
import br.com.domus.platform.devices.adapter.DeviceSecrets;
import br.com.domus.platform.identity.entity.AppUser;
import br.com.domus.platform.identity.repository.AppUserRepository;
import br.com.domus.platform.identity.entity.Membership;
import br.com.domus.platform.identity.repository.MembershipRepository;
import br.com.domus.platform.identity.entity.Role;
import java.time.Duration;
import java.util.UUID;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Assinatura de eventos Dahua ponta a ponta (API -> adaptador -> equipamento falso -> fluxo de eventos -> banco).
 * Prova que só o evento que devolve o identificador do nosso comando leva a CONFIRMED.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DahuaEventsIT extends AbstractIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired OperatorCompanyRepository companies;
    @Autowired CondominiumRepository condominiums;
    @Autowired AppUserRepository users;
    @Autowired MembershipRepository memberships;
    @Autowired AccessCommandService commandService;
    @Autowired DahuaEventSubscriber subscriber;
    @MockitoBean DeviceSecrets secrets;

    UUID condo;
    FakeDahuaDevice fake;

    @BeforeAll
    void seed() throws Exception {
        OperatorCompany c = companies.save(new OperatorCompany("Dahua Company", "dahua-0000000000"));
        condo = condominiums.save(new Condominium(c.getId(), "Dahua Main", null)).getId();
        staff("dh-manager", Role.CONDO_MANAGER);
        staff("dh-op", Role.OPERATOR);
        fake = new FakeDahuaDevice("secret-pass");
    }

    /** O mock é reiniciado a cada teste, então as credenciais são definidas antes de cada um. */
    @BeforeEach
    void credentials() {
        when(secrets.get(anyString())).thenAnswer(i -> "DH_USER".equals(i.getArgument(0)) ? "admin"
                : "DH_PASS".equals(i.getArgument(0)) ? "secret-pass" : null);
    }

    @AfterAll
    void stop() {
        fake.stop();
    }

    private void staff(String subject, Role role) {
        AppUser u = users.save(new AppUser(subject, subject + "@example.invalid", subject));
        memberships.save(new Membership(u.getId(), role, null, condo));
    }

    private static RequestPostProcessor as(String s) {
        return jwt().jwt(j -> j.subject(s));
    }

    private String url(String suffix) {
        return "/api/v1/condominiums/" + condo + suffix;
    }

    private JsonNode body(MvcResult r) throws Exception {
        return json.readTree(r.getResponse().getContentAsString());
    }

    private String registerDahuaAndConnect(String name) throws Exception {
        String config = "{\"baseUrl\":\"" + fake.baseUrl() + "\",\"userEnv\":\"DH_USER\",\"passwordEnv\":\"DH_PASS\"}";
        MvcResult r = mvc.perform(post(url("/devices")).with(as("dh-manager")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"" + name + "\",\"kind\":\"ACCESS_CONTROLLER\",\"adapter\":\"DAHUA_CGI\","
                        + "\"entrance\":\"main\",\"config\":" + config + "}")).andExpect(status().isCreated()).andReturn();
        int before = fake.streamCount();
        subscriber.reconcile();
        Awaitility.await().atMost(Duration.ofSeconds(10)).until(() -> fake.streamCount() > before);
        return body(r).get("id").asString();
    }

    private String openDoor(String deviceId, String key) throws Exception {
        mvc.perform(post("/api/v1/operator/shifts/start").with(as("dh-op")));
        mvc.perform(post(url("/calls")).with(as("dh-op")).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isCreated());
        String call = body(mvc.perform(post("/api/v1/operator/calls/next").with(as("dh-op")))
                .andExpect(status().isOk()).andReturn()).get("id").asString();
        MvcResult r = mvc.perform(post(url("/devices/" + deviceId + "/open")).with(as("dh-op"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"callId\":\"" + call + "\",\"justification\":\"Morador autorizou por telefone\","
                        + "\"idempotencyKey\":\"" + key + "\"}")).andReturn();
        assertThat(r.getResponse().getStatus()).isEqualTo(202);
        return body(r).get("id").asString();
    }

    private String state(String commandId) throws Exception {
        return body(mvc.perform(get(url("/commands/" + commandId)).with(as("dh-manager"))).andReturn()).get("state").asString();
    }

    @Test
    void doorIsConfirmedOnlyWhenTheEventEchoesOurCommandId() throws Exception {
        fake.echoUserIdOnOpen = true;
        String deviceId = registerDahuaAndConnect("Controladora eco");
        String id = openDoor(deviceId, "dh-echo");

        Awaitility.await().atMost(Duration.ofSeconds(10)).until(() -> "CONFIRMED".equals(state(id)));
        mvc.perform(get(url("/commands/" + id)).with(as("dh-manager")))
                .andExpect(jsonPath("$.evidence").value("DEVICE_EVENT:DOOR_OPENED"));
    }

    @Test
    void anUncorrelatedOpeningEventNeverConfirmsAndTheCommandEndsUnknown() throws Exception {
        fake.echoUserIdOnOpen = false;
        String deviceId = registerDahuaAndConnect("Controladora sem eco");
        String id = openDoor(deviceId, "dh-noecho");

        // alguém abre a porta com cartão no mesmo instante: evento legítimo, mas não é a resposta ao nosso comando
        fake.pushEvent("Code=AccessControl;action=Pulse;index=0;data={\"Status\":1,\"UserID\":\"1001\"}");
        fake.pushEvent("Code=DoorStatus;action=Pulse;index=0;data={\"Status\":\"Open\"}");
        Thread.sleep(2500);

        assertThat(state(id)).isEqualTo("SENT");
        assertThat(commandService.expireUnconfirmed()).isGreaterThanOrEqualTo(1);
        mvc.perform(get(url("/commands/" + id)).with(as("dh-manager")))
                .andExpect(jsonPath("$.state").value("UNKNOWN"))
                .andExpect(jsonPath("$.failureReason").value("NO_CONFIRMATION"));
    }

    @Test
    void theSubscriptionReconnectsAfterTheEquipmentDropsTheStream() throws Exception {
        fake.echoUserIdOnOpen = true;
        String deviceId = registerDahuaAndConnect("Controladora queda");
        int before = fake.streamCount();
        fake.dropStreams();
        Awaitility.await().atMost(Duration.ofSeconds(15)).until(() -> fake.streamCount() >= 1);
        assertThat(before).isGreaterThanOrEqualTo(1);

        String id = openDoor(deviceId, "dh-reconnect");
        Awaitility.await().atMost(Duration.ofSeconds(10)).until(() -> "CONFIRMED".equals(state(id)));
    }
}
