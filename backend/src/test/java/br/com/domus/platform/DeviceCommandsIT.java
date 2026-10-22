package br.com.domus.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.domus.platform.commercial.Block;
import br.com.domus.platform.commercial.BlockRepository;
import br.com.domus.platform.commercial.Condominium;
import br.com.domus.platform.commercial.CondominiumRepository;
import br.com.domus.platform.commercial.OperatorCompany;
import br.com.domus.platform.commercial.OperatorCompanyRepository;
import br.com.domus.platform.commercial.Unit;
import br.com.domus.platform.commercial.UnitRepository;
import br.com.domus.platform.devices.AccessCommandService;
import br.com.domus.platform.devices.DeviceEventRepository;
import br.com.domus.platform.identity.AppUser;
import br.com.domus.platform.identity.AppUserRepository;
import br.com.domus.platform.identity.Membership;
import br.com.domus.platform.identity.MembershipRepository;
import br.com.domus.platform.identity.Role;
import br.com.domus.platform.residents.Resident;
import br.com.domus.platform.residents.ResidentRepository;
import br.com.domus.platform.residents.ResidentType;
import br.com.domus.platform.shared.audit.AuditLogRepository;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Comandos físicos: confirmação verificável, idempotência, falhas, SSRF e adaptadores. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DeviceCommandsIT extends AbstractIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired OperatorCompanyRepository companies;
    @Autowired CondominiumRepository condominiums;
    @Autowired BlockRepository blocks;
    @Autowired UnitRepository units;
    @Autowired ResidentRepository residents;
    @Autowired AppUserRepository users;
    @Autowired MembershipRepository memberships;
    @Autowired DeviceEventRepository deviceEvents;
    @Autowired AuditLogRepository auditLog;
    @Autowired AccessCommandService commandService;

    UUID condo, otherCondo, unit;
    HttpServer stub;
    final AtomicInteger stubOpenHits = new AtomicInteger();

    @BeforeAll
    void seed() throws Exception {
        OperatorCompany c = companies.save(new OperatorCompany("P6 Company", "p6-00000000000"));
        condo = condominiums.save(new Condominium(c.getId(), "P6 Main", null)).getId();
        otherCondo = condominiums.save(new Condominium(c.getId(), "P6 Other", null)).getId();
        Block b = blocks.save(new Block(condo, "A"));
        unit = units.save(new Unit(condo, b.getId(), "101")).getId();
        Resident r = residents.save(new Resident(condo, unit, "p6-res", null, null, ResidentType.OWNER));
        AppUser ru = users.save(new AppUser("p6-res", "p6-res@example.invalid", "p6-res"));
        memberships.save(Membership.forResident(ru.getId(), condo, r.getId()));
        staff("p6-manager", Role.CONDO_MANAGER, condo);
        staff("p6-op", Role.OPERATOR, condo);
        staff("p6-op2", Role.OPERATOR, condo);
        staff("p6-op-other", Role.OPERATOR, otherCondo);

        stub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        stub.createContext("/open", ex -> {
            stubOpenHits.incrementAndGet();
            ex.sendResponseHeaders(200, -1);
            ex.close();
        });
        stub.createContext("/status", ex -> {
            ex.sendResponseHeaders(200, -1);
            ex.close();
        });
        stub.createContext("/onvif/device_service", ex -> {
            byte[] body = ("<s:Envelope xmlns:s=\"http://www.w3.org/2003/05/soap-envelope\"><s:Body>"
                    + "<tds:GetDeviceInformationResponse xmlns:tds=\"http://www.onvif.org/ver10/device/wsdl\">"
                    + "<tds:Manufacturer>Acme</tds:Manufacturer><tds:Model>Cam-1</tds:Model>"
                    + "</tds:GetDeviceInformationResponse></s:Body></s:Envelope>").getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        stub.start();
    }

    @AfterAll
    void stop() {
        stub.stop(0);
    }

    private void staff(String subject, Role role, UUID c) {
        AppUser u = users.save(new AppUser(subject, subject + "@example.invalid", subject));
        memberships.save(new Membership(u.getId(), role, null, c));
    }

    private static RequestPostProcessor as(String s) {
        return jwt().jwt(j -> j.subject(s));
    }

    private JsonNode body(MvcResult r) throws Exception {
        return json.readTree(r.getResponse().getContentAsString());
    }

    private String url(String suffix) {
        return "/api/v1/condominiums/" + condo + suffix;
    }

    private JsonNode registerDevice(String name, String kind, String adapter, String config) throws Exception {
        MvcResult r = mvc.perform(post(url("/devices")).with(as("p6-manager")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"" + name + "\",\"kind\":\"" + kind + "\",\"adapter\":\"" + adapter
                        + "\",\"entrance\":\"main\",\"config\":" + config + "}"))
                .andExpect(status().isCreated()).andReturn();
        return body(r);
    }

    private String assignedCall(String operator) throws Exception {
        mvc.perform(post("/api/v1/operator/shifts/start").with(as(operator)));
        mvc.perform(post(url("/calls")).with(as(operator)).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isCreated());
        return body(mvc.perform(post("/api/v1/operator/calls/next").with(as(operator)))
                .andExpect(status().isOk()).andReturn()).get("id").asString();
    }

    private MvcResult open(String operator, String deviceId, String basis, String key) throws Exception {
        return mvc.perform(post(url("/devices/" + deviceId + "/open")).with(as(operator))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{" + basis + ",\"justification\":\"Morador autorizou por telefone\",\"idempotencyKey\":\"" + key + "\"}"))
                .andReturn();
    }

    private String awaitState(String commandId, String expected) throws Exception {
        String state = "";
        for (int i = 0; i < 40; i++) {
            state = body(mvc.perform(get(url("/commands/" + commandId)).with(as("p6-manager"))).andReturn())
                    .get("state").asString();
            if (state.equals(expected)) {
                return state;
            }
            Thread.sleep(100);
        }
        return state;
    }

    @Test
    void doorIsConfirmedOnlyByADeviceEventAndSecretsAreNeverListed() throws Exception {
        JsonNode device = registerDevice("Portão A", "ACCESS_CONTROLLER", "SIMULATOR", "{}");
        assertThat(device.get("apiKey").asString()).isNotBlank();
        mvc.perform(get(url("/devices")).with(as("p6-manager")))
                .andExpect(jsonPath("$.items[0].apiKey").doesNotExist());
        String call = assignedCall("p6-op");
        MvcResult r = open("p6-op", device.get("id").asString(), "\"callId\":\"" + call + "\"", "k-confirm-1");
        assertThat(r.getResponse().getStatus()).isEqualTo(202);
        String id = body(r).get("id").asString();
        assertThat(body(r).get("state").asString()).isIn("PENDING", "SENT", "CONFIRMED");
        assertThat(awaitState(id, "CONFIRMED")).isEqualTo("CONFIRMED");
        mvc.perform(get(url("/commands/" + id)).with(as("p6-manager")))
                .andExpect(jsonPath("$.evidence").value("DEVICE_EVENT:DOOR_OPENED"));
    }

    @Test
    void repeatedRequestWithSameKeyOpensOnlyOnce() throws Exception {
        JsonNode device = registerDevice("Portão B", "ACCESS_CONTROLLER", "SIMULATOR", "{}");
        UUID deviceId = UUID.fromString(device.get("id").asString());
        String call = assignedCall("p6-op");
        String basis = "\"callId\":\"" + call + "\"";
        String first = body(open("p6-op", deviceId.toString(), basis, "k-idem-1")).get("id").asString();
        String second = body(open("p6-op", deviceId.toString(), basis, "k-idem-1")).get("id").asString();
        assertThat(second).isEqualTo(first);
        awaitState(first, "CONFIRMED");
        assertThat(deviceEvents.countByDeviceIdAndType(deviceId, "DOOR_OPENED")).isEqualTo(1);
        // outra pessoa tentando reaproveitar a mesma chave não é aceita
        assertThat(open("p6-op2", deviceId.toString(), basis, "k-idem-1").getResponse().getStatus()).isEqualTo(409);
    }

    @Test
    void openRequiresAValidAuthorizationBasisAndIsAudited() throws Exception {
        JsonNode device = registerDevice("Portão C", "ACCESS_CONTROLLER", "SIMULATOR", "{}");
        String id = device.get("id").asString();
        assertThat(open("p6-op", id, "\"justification2\":1", "k-none").getResponse().getStatus()).isEqualTo(400);
        assertThat(open("p6-op", id, "\"callId\":\"" + UUID.randomUUID() + "\"", "k-fake").getResponse().getStatus())
                .isEqualTo(403);
        String call = assignedCall("p6-op");
        // atendimento atribuído a outro operador não serve de base
        assertThat(open("p6-op2", id, "\"callId\":\"" + call + "\"", "k-other-op").getResponse().getStatus())
                .isEqualTo(403);
        assertThat(auditLog.findByCondominiumIdOrderByOccurredAtDesc(condo))
                .anyMatch(e -> e.getAction().startsWith("door.open.") && e.getOutcome().equals("DENIED"));
    }

    @Test
    void justificationIsMandatory() throws Exception {
        JsonNode device = registerDevice("Portão D", "ACCESS_CONTROLLER", "SIMULATOR", "{}");
        String call = assignedCall("p6-op");
        mvc.perform(post(url("/devices/" + device.get("id").asString() + "/open")).with(as("p6-op"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"callId\":\"" + call + "\",\"justification\":\"ok\",\"idempotencyKey\":\"k-just\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void noConfirmationBecomesUnknownAndALateEventCanStillResolveIt() throws Exception {
        JsonNode device = registerDevice("Portão E", "ACCESS_CONTROLLER", "SIMULATOR", "{\"behavior\":\"SILENT\"}");
        String deviceId = device.get("id").asString();
        String call = assignedCall("p6-op");
        String id = body(open("p6-op", deviceId, "\"callId\":\"" + call + "\"", "k-silent")).get("id").asString();
        assertThat(awaitState(id, "SENT")).isEqualTo("SENT"); // aceito, mas ainda sem prova de abertura
        Thread.sleep(2300);
        assertThat(commandService.expireUnconfirmed()).isGreaterThanOrEqualTo(1);
        mvc.perform(get(url("/commands/" + id)).with(as("p6-manager")))
                .andExpect(jsonPath("$.state").value("UNKNOWN"))
                .andExpect(jsonPath("$.failureReason").value("NO_CONFIRMATION"));

        String apiKey = device.get("apiKey").asString();
        mvc.perform(post("/api/v1/devices/" + deviceId + "/events").header("X-Device-Key", "wrong")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"type\":\"DOOR_OPENED\",\"commandId\":\"" + id + "\",\"occurredAt\":\"" + Instant.now() + "\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/devices/" + deviceId + "/events").header("X-Device-Key", apiKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"type\":\"DOOR_OPENED\",\"commandId\":\"" + id + "\",\"occurredAt\":\"" + Instant.now() + "\"}"))
                .andExpect(status().isAccepted());
        mvc.perform(get(url("/commands/" + id)).with(as("p6-manager"))).andExpect(jsonPath("$.state").value("CONFIRMED"));
    }

    @Test
    void anEventFromAnotherDeviceNeverConfirmsTheCommand() throws Exception {
        JsonNode a = registerDevice("Portão F", "ACCESS_CONTROLLER", "SIMULATOR", "{\"behavior\":\"SILENT\"}");
        JsonNode b = registerDevice("Portão G", "ACCESS_CONTROLLER", "SIMULATOR", "{\"behavior\":\"SILENT\"}");
        String call = assignedCall("p6-op");
        String id = body(open("p6-op", a.get("id").asString(), "\"callId\":\"" + call + "\"", "k-cross")).get("id").asString();
        mvc.perform(post("/api/v1/devices/" + b.get("id").asString() + "/events").header("X-Device-Key", b.get("apiKey").asString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"type\":\"DOOR_OPENED\",\"commandId\":\"" + id + "\",\"occurredAt\":\"" + Instant.now() + "\"}"))
                .andExpect(status().isAccepted());
        mvc.perform(get(url("/commands/" + id)).with(as("p6-manager"))).andExpect(jsonPath("$.state").value("SENT"));
    }

    @Test
    void rejectedAndOfflineDevicesFailExplicitly() throws Exception {
        JsonNode rej = registerDevice("Portão H", "ACCESS_CONTROLLER", "SIMULATOR", "{\"behavior\":\"REJECT\"}");
        JsonNode off = registerDevice("Portão I", "ACCESS_CONTROLLER", "SIMULATOR", "{\"behavior\":\"UNREACHABLE\"}");
        String call = assignedCall("p6-op");
        String basis = "\"callId\":\"" + call + "\"";
        JsonNode r1 = body(open("p6-op", rej.get("id").asString(), basis, "k-rej"));
        assertThat(r1.get("state").asString()).isEqualTo("FAILED");
        assertThat(r1.get("failureReason").asString()).isEqualTo("REJECTED_BY_DEVICE");
        JsonNode r2 = body(open("p6-op", off.get("id").asString(), basis, "k-off"));
        assertThat(r2.get("state").asString()).isEqualTo("FAILED");
        assertThat(r2.get("failureReason").asString()).isEqualTo("DEVICE_OFFLINE");
    }

    @Test
    void validatedCredentialAuthorizesExactlyOneCommand() throws Exception {
        JsonNode device = registerDevice("Portão J", "ACCESS_CONTROLLER", "SIMULATOR", "{}");
        Instant now = Instant.now();
        String token = body(mvc.perform(post(url("/me/invitations")).with(as("p6-res")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"visitorName\":\"Visita\",\"kind\":\"VISITOR\",\"validFrom\":\"" + now.minusSeconds(30)
                        + "\",\"validUntil\":\"" + now.plus(1, ChronoUnit.HOURS) + "\",\"maxUses\":1}"))
                .andReturn()).get("token").asString();
        JsonNode validation = body(mvc.perform(post(url("/access/validate")).with(as("p6-op"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\":\"" + token + "\",\"entrance\":\"main\"}")).andReturn());
        assertThat(validation.get("granted").asBoolean()).isTrue();
        String attempt = validation.get("attemptId").asString();
        String id = device.get("id").asString();
        assertThat(open("p6-op", id, "\"attemptId\":\"" + attempt + "\"", "k-att-1").getResponse().getStatus()).isEqualTo(202);
        assertThat(open("p6-op", id, "\"attemptId\":\"" + attempt + "\"", "k-att-2").getResponse().getStatus()).isEqualTo(409);
    }

    @Test
    void operatorOfAnotherCondominiumAndNonControllersAreRejected() throws Exception {
        JsonNode device = registerDevice("Portão K", "ACCESS_CONTROLLER", "SIMULATOR", "{}");
        mvc.perform(post(url("/devices/" + device.get("id").asString() + "/open")).with(as("p6-op-other"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"callId\":\"" + UUID.randomUUID() + "\",\"justification\":\"tentativa indevida\",\"idempotencyKey\":\"k-x\"}"))
                .andExpect(status().isNotFound());
        mvc.perform(post(url("/devices")).with(as("p6-op")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"X\",\"kind\":\"ACCESS_CONTROLLER\",\"adapter\":\"SIMULATOR\",\"entrance\":\"main\"}"))
                .andExpect(status().isForbidden());
        // câmera ONVIF não pode ser cadastrada como controladora (não controla portas)
        mvc.perform(post(url("/devices")).with(as("p6-manager")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Y\",\"kind\":\"ACCESS_CONTROLLER\",\"adapter\":\"ONVIF\",\"entrance\":\"main\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void httpOkIsNeverTreatedAsProofThatTheDoorOpened() throws Exception {
        String base = "http://127.0.0.1:" + stub.getAddress().getPort();
        JsonNode device = registerDevice("Controladora HTTP", "ACCESS_CONTROLLER", "GENERIC_HTTP",
                "{\"baseUrl\":\"" + base + "\",\"openPath\":\"/open\",\"statusPath\":\"/status\"}");
        String call = assignedCall("p6-op");
        int hitsBefore = stubOpenHits.get();
        String id = body(open("p6-op", device.get("id").asString(), "\"callId\":\"" + call + "\"", "k-http")).get("id").asString();
        assertThat(stubOpenHits.get()).isEqualTo(hitsBefore + 1);
        Thread.sleep(500);
        mvc.perform(get(url("/commands/" + id)).with(as("p6-manager"))).andExpect(jsonPath("$.state").value("SENT"));
    }

    @Test
    void hostsOutsideThePolicyAreNeverContacted() throws Exception {
        JsonNode device = registerDevice("Controladora SSRF", "ACCESS_CONTROLLER", "GENERIC_HTTP",
                "{\"baseUrl\":\"http://169.254.169.254\",\"openPath\":\"/open\"}");
        mvc.perform(post(url("/devices/" + device.get("id").asString() + "/refresh-status")).with(as("p6-op")))
                .andExpect(jsonPath("$.status").value("OFFLINE"));
        String call = assignedCall("p6-op");
        JsonNode r = body(open("p6-op", device.get("id").asString(), "\"callId\":\"" + call + "\"", "k-ssrf"));
        assertThat(r.get("state").asString()).isEqualTo("FAILED");
        assertThat(r.get("failureReason").asString()).isEqualTo("DEVICE_OFFLINE");
    }

    @Test
    void onvifStatusIsReadFromTheStandardOperationAndOverviewShowsHealth() throws Exception {
        String base = "http://127.0.0.1:" + stub.getAddress().getPort();
        JsonNode cam = registerDevice("Câmera ONVIF", "CAMERA", "ONVIF", "{\"baseUrl\":\"" + base + "\"}");
        mvc.perform(post(url("/devices/" + cam.get("id").asString() + "/refresh-status")).with(as("p6-op")))
                .andExpect(jsonPath("$.status").value("ONLINE"));
        mvc.perform(get("/api/v1/operator/overview").with(as("p6-op")))
                .andExpect(jsonPath("$[?(@.name=='P6 Main')].deviceHealth").isNotEmpty());
    }
}
