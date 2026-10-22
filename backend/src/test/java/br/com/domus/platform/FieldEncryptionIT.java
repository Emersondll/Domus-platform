package br.com.domus.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.domus.platform.commercial.entity.Block;
import br.com.domus.platform.commercial.repository.BlockRepository;
import br.com.domus.platform.commercial.entity.Condominium;
import br.com.domus.platform.commercial.repository.CondominiumRepository;
import br.com.domus.platform.commercial.entity.OperatorCompany;
import br.com.domus.platform.commercial.repository.OperatorCompanyRepository;
import br.com.domus.platform.commercial.entity.Unit;
import br.com.domus.platform.commercial.repository.UnitRepository;
import br.com.domus.platform.identity.entity.AppUser;
import br.com.domus.platform.identity.repository.AppUserRepository;
import br.com.domus.platform.identity.entity.Membership;
import br.com.domus.platform.identity.repository.MembershipRepository;
import br.com.domus.platform.privacy.service.PiiEncryptionMigration;
import br.com.domus.platform.residents.entity.Resident;
import br.com.domus.platform.residents.repository.ResidentRepository;
import br.com.domus.platform.residents.entity.ResidentType;
import br.com.domus.platform.shared.crypto.FieldCipher;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/** Dados pessoais cifrados no banco e transparentes na API; placa única por índice cego; migração de dados legados. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FieldEncryptionIT extends AbstractIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired FieldCipher cipher;
    @Autowired PiiEncryptionMigration migration;
    @Autowired OperatorCompanyRepository companies;
    @Autowired CondominiumRepository condominiums;
    @Autowired BlockRepository blocks;
    @Autowired UnitRepository units;
    @Autowired ResidentRepository residents;
    @Autowired AppUserRepository users;
    @Autowired MembershipRepository memberships;

    UUID condo, unit;
    UUID residentId;

    @BeforeAll
    void seed() {
        OperatorCompany c = companies.save(new OperatorCompany("Enc Company", "enc-0000000000"));
        condo = condominiums.save(new Condominium(c.getId(), "Enc Main", null)).getId();
        Block b = blocks.save(new Block(condo, "A"));
        unit = units.save(new Unit(condo, b.getId(), "101")).getId();
        Resident r = residents.save(new Resident(condo, unit, "Joana Dâmaso Exemplo", "joana.enc@example.invalid",
                "+55 11 97777-0000", ResidentType.OWNER));
        residentId = r.getId();
        AppUser u = users.save(new AppUser("enc-res", "enc-res@example.invalid", "Joana Exemplo Conta"));
        memberships.save(Membership.forResident(u.getId(), condo, r.getId()));
    }

    private static RequestPostProcessor as(String s) {
        return jwt().jwt(j -> j.subject(s));
    }

    private String raw(String sql, Object... params) {
        return jdbc.sql(sql).params(params).query(String.class).single();
    }

    @Test
    void personalDataIsCipheredAtRestAndPlainThroughTheApi() throws Exception {
        String name = raw("SELECT full_name FROM resident WHERE id = ?", residentId);
        String email = raw("SELECT email FROM resident WHERE id = ?", residentId);
        String phone = raw("SELECT phone FROM resident WHERE id = ?", residentId);
        assertThat(name).startsWith("enc:v1:").doesNotContain("Joana");
        assertThat(email).startsWith("enc:v1:").doesNotContain("joana.enc");
        assertThat(phone).startsWith("enc:v1:").doesNotContain("97777");
        assertThat(raw("SELECT email FROM app_user WHERE external_subject = ?", "enc-res")).startsWith("enc:v1:");
        assertThat(raw("SELECT display_name FROM app_user WHERE external_subject = ?", "enc-res")).startsWith("enc:v1:");

        mvc.perform(get("/api/v1/condominiums/" + condo + "/me/profile").with(as("enc-res")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fullName").value("Joana Dâmaso Exemplo"))
                .andExpect(jsonPath("$.email").value("joana.enc@example.invalid"))
                .andExpect(jsonPath("$.phone").value("+55 11 97777-0000"));
    }

    @Test
    void updatingContactStoresTheNewValueCiphered() throws Exception {
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .put("/api/v1/condominiums/" + condo + "/me/profile").with(as("enc-res"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"novo.enc@example.invalid\",\"phone\":\"+55 11 96666-0000\"}"))
                .andExpect(status().isOk());
        assertThat(raw("SELECT phone FROM resident WHERE id = ?", residentId)).startsWith("enc:v1:").doesNotContain("96666");
        mvc.perform(get("/api/v1/condominiums/" + condo + "/me/profile").with(as("enc-res")))
                .andExpect(jsonPath("$.phone").value("+55 11 96666-0000"));
    }

    @Test
    void vehiclePlateIsCipheredButStillUniquePerCondominiumByBlindIndex() throws Exception {
        String body = "{\"plate\":\"enc-1a23\",\"model\":\"Hatch\",\"color\":\"prata\"}";
        mvc.perform(post("/api/v1/condominiums/" + condo + "/me/vehicles").with(as("enc-res"))
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isCreated())
                .andExpect(jsonPath("$.plate").value("ENC1A23"));
        assertThat(raw("SELECT plate FROM vehicle WHERE condominium_id = ?", condo)).startsWith("enc:v1:").doesNotContain("ENC1A23");
        assertThat(raw("SELECT plate_hash FROM vehicle WHERE condominium_id = ?", condo)).isEqualTo(cipher.blindIndex("ENC1A23"));
        // mesma placa com outra formatação: continua conflito
        mvc.perform(post("/api/v1/condominiums/" + condo + "/me/vehicles").with(as("enc-res"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"plate\":\"ENC-1A23\"}")).andExpect(status().isConflict());
    }

    @Test
    void legacyPlaintextRowsStayReadableAndAreEncryptedByTheMigration() throws Exception {
        UUID legacy = UUID.randomUUID();
        jdbc.sql("INSERT INTO resident (id, condominium_id, unit_id, full_name, email, phone, resident_type, active) "
                + "VALUES (?, ?, ?, 'Legado Exemplo', 'legado@example.invalid', '+55 11 95555-0000', 'TENANT', true)")
                .params(legacy, condo, unit).update();
        UUID legacyVehicle = UUID.randomUUID();
        jdbc.sql("INSERT INTO vehicle (id, condominium_id, unit_id, plate, model, color) VALUES (?, ?, ?, 'LEG1A23', 'x', 'y')")
                .params(legacyVehicle, condo, unit).update();

        // lido como está, mesmo sem prefixo
        assertThat(residents.findById(legacy).orElseThrow().getFullName()).isEqualTo("Legado Exemplo");

        int updated = migration.migrate();
        assertThat(updated).isGreaterThanOrEqualTo(4);
        assertThat(raw("SELECT full_name FROM resident WHERE id = ?", legacy)).startsWith("enc:v1:").doesNotContain("Legado");
        assertThat(raw("SELECT plate FROM vehicle WHERE id = ?", legacyVehicle)).startsWith("enc:v1:");
        assertThat(raw("SELECT plate_hash FROM vehicle WHERE id = ?", legacyVehicle)).isEqualTo(cipher.blindIndex("LEG1A23"));
        assertThat(residents.findById(legacy).orElseThrow().getFullName()).isEqualTo("Legado Exemplo");
        assertThat(migration.migrate()).isZero(); // idempotente: nada mais a fazer
    }
}
