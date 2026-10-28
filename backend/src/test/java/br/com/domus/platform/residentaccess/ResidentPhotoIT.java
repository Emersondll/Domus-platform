package br.com.domus.platform.residentaccess;

import br.com.domus.platform.AbstractIntegrationTest;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
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
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.UUID;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Foto de cadastro: consentimento específico, cifra em repouso, revogação que apaga e visão só do titular. Imagens sintéticas. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ResidentPhotoIT extends AbstractIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired OperatorCompanyRepository companies;
    @Autowired CondominiumRepository condominiums;
    @Autowired BlockRepository blocks;
    @Autowired UnitRepository units;
    @Autowired ResidentRepository residents;
    @Autowired AppUserRepository users;
    @Autowired MembershipRepository memberships;

    UUID condo, otherCondo, ana, kid, zed;

    @BeforeAll
    void seed() {
        OperatorCompany c = companies.save(new OperatorCompany("RP Company", "rp-00000000000"));
        condo = condominiums.save(new Condominium(c.getId(), "RP Main", null)).getId();
        otherCondo = condominiums.save(new Condominium(c.getId(), "RP Other", null)).getId();
        UUID unit = units.save(new Unit(condo, blocks.save(new Block(condo, "P")).getId(), "31")).getId();
        ana = resident("rp-ana", ResidentType.OWNER, unit);
        kid = resident("rp-kid", ResidentType.DEPENDENT, unit);
        resident("rp-bia", ResidentType.TENANT, unit);
        zed = resident("rp-zed", ResidentType.OWNER, unit); // quem é anonimizado no último teste
        member("rp-manager", Role.CONDO_MANAGER, condo);
        member("rp-operator", Role.OPERATOR, condo);
        member("rp-other-manager", Role.CONDO_MANAGER, otherCondo);
    }

    private UUID resident(String subject, ResidentType type, UUID unit) {
        Resident r = residents.save(new Resident(condo, unit, subject, null, null, type));
        AppUser u = users.save(new AppUser(subject, subject + "@example.invalid", subject));
        memberships.save(Membership.forResident(u.getId(), condo, r.getId()));
        return r.getId();
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

    /** Imagem sintética (gradiente), nunca uma foto de pessoa. */
    private static byte[] png(int w, int h) throws Exception {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        for (int x = 0; x < w; x++) {
            for (int y = 0; y < h; y++) {
                img.setRGB(x, y, new Color(x % 256, y % 256, (x + y) % 256).getRGB());
            }
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return out.toByteArray();
    }

    private String grant(String who) throws Exception {
        JsonNode status = json.readTree(mvc.perform(get(base() + "/me/photo/status").with(as(who)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.enabled").value(true))
                .andReturn().getResponse().getContentAsString());
        String res = mvc.perform(post(base() + "/me/consents").with(as(who)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"textVersion\":\"" + status.get("textVersion").asString() + "\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return json.readTree(res).get("id").asString();
    }

    @Test
    void photoNeedsConsentAndIsEncryptedAtRestAndOnlyTheOwnerSeesIt() throws Exception {
        byte[] image = png(400, 300);
        mvc.perform(put(base() + "/me/photo").with(as("rp-ana")).contentType(MediaType.IMAGE_PNG).content(image))
                .andExpect(status().isConflict()); // sem consentimento
        grant("rp-ana");
        mvc.perform(put(base() + "/me/photo").with(as("rp-ana")).contentType(MediaType.IMAGE_PNG).content(image))
                .andExpect(status().isOk()).andExpect(jsonPath("$.hasPhoto").value(true));
        mvc.perform(get(base() + "/me/photo").with(as("rp-ana"))).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")))
                .andExpect(header().string("Content-Type", "image/jpeg"));
        assertThat(jdbc.queryForObject("select content from resident_photo where resident_id = ?", String.class, ana))
                .startsWith("enc:v1:");

        // outro morador da mesma unidade, equipe e outro condomínio não leem a imagem
        mvc.perform(get(base() + "/me/photo").with(as("rp-bia"))).andExpect(status().isNotFound());
        for (String who : new String[] {"rp-manager", "rp-operator", "rp-other-manager"}) {
            mvc.perform(get(base() + "/me/photo").with(as(who))).andExpect(status().isNotFound());
        }
        mvc.perform(get(base() + "/me/photo")).andExpect(status().isUnauthorized());
        // a ficha da portaria só diz "sim ou não"
        mvc.perform(get(base() + "/residents/" + ana + "/photo-status").with(as("rp-operator")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.hasPhoto").value(true));
        mvc.perform(get(base() + "/residents/" + ana + "/photo-status").with(as("rp-other-manager")))
                .andExpect(status().isNotFound());
    }

    @Test
    void revokingTheConsentDeletesThePhotoAtOnce() throws Exception {
        String consent = grant("rp-bia");
        mvc.perform(put(base() + "/me/photo").with(as("rp-bia")).contentType(MediaType.IMAGE_PNG).content(png(320, 320)))
                .andExpect(status().isOk());
        mvc.perform(post(base() + "/me/consents/" + consent + "/revoke").with(as("rp-bia"))).andExpect(status().isNoContent());
        mvc.perform(get(base() + "/me/photo").with(as("rp-bia"))).andExpect(status().isNotFound());
        assertThat(jdbc.queryForObject("select count(*) from resident_photo where resident_id = (select resident_id "
                + "from privacy_consent where id = ?::uuid)", Long.class, consent)).isZero();
        // a prova do aceite e da revogação permanece
        assertThat(jdbc.queryForObject("select revoked_at is not null from privacy_consent where id = ?::uuid",
                Boolean.class, consent)).isTrue();
        mvc.perform(put(base() + "/me/photo").with(as("rp-bia")).contentType(MediaType.IMAGE_PNG).content(png(320, 320)))
                .andExpect(status().isConflict()); // sem consentimento vigente de novo
    }

    @Test
    void invalidImagesAndStaleConsentTextAreRefused() throws Exception {
        grant("rp-ana");
        mvc.perform(put(base() + "/me/photo").with(as("rp-ana")).contentType(MediaType.APPLICATION_OCTET_STREAM)
                .content("isto nao e imagem".getBytes())).andExpect(status().isBadRequest());
        mvc.perform(put(base() + "/me/photo").with(as("rp-ana")).contentType(MediaType.IMAGE_PNG).content(png(64, 64)))
                .andExpect(status().isBadRequest()); // pequena demais
        mvc.perform(put(base() + "/me/photo").with(as("rp-ana")).contentType(MediaType.IMAGE_PNG).content(png(900, 200)))
                .andExpect(status().isBadRequest()); // proporção absurda
        mvc.perform(put(base() + "/me/photo").with(as("rp-ana")).contentType(MediaType.IMAGE_PNG)
                .content(new byte[2 * 1024 * 1024 + 1])).andExpect(status().isBadRequest());
        mvc.perform(post(base() + "/me/consents").with(as("rp-ana")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"textVersion\":\"texto-antigo\"}")).andExpect(status().isConflict());
    }

    @Test
    void dependentsAreBlockedAndStaffCannotGrantOrUpload() throws Exception {
        mvc.perform(post(base() + "/me/consents").with(as("rp-kid")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"textVersion\":\"2026-10-v1\"}")).andExpect(status().isConflict());
        for (String who : new String[] {"rp-manager", "rp-operator"}) {
            mvc.perform(post(base() + "/me/consents").with(as(who)).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"textVersion\":\"2026-10-v1\"}")).andExpect(status().isNotFound());
            mvc.perform(put(base() + "/me/photo").with(as(who)).contentType(MediaType.IMAGE_PNG).content(png(300, 300)))
                    .andExpect(status().isNotFound());
        }
    }

    @Test
    void ownerCanDeleteAndTheDataSubjectRightsCoverThePhoto() throws Exception {
        grant("rp-zed");
        mvc.perform(put(base() + "/me/photo").with(as("rp-zed")).contentType(MediaType.IMAGE_PNG).content(png(300, 300)))
                .andExpect(status().isOk());
        mvc.perform(get(base() + "/residents/" + zed + "/export").with(as("rp-manager"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.registrationPhoto.jpegBase64").isNotEmpty())
                .andExpect(jsonPath("$.consents[0].textVersion").value("2026-10-v1"));
        mvc.perform(delete(base() + "/me/photo").with(as("rp-zed"))).andExpect(status().isNoContent());
        mvc.perform(get(base() + "/me/photo").with(as("rp-zed"))).andExpect(status().isNotFound());

        mvc.perform(put(base() + "/me/photo").with(as("rp-zed")).contentType(MediaType.IMAGE_PNG).content(png(300, 300)))
                .andExpect(status().isOk());
        mvc.perform(post(base() + "/residents/" + zed + "/anonymize").with(as("rp-manager"))).andExpect(status().isNoContent());
        assertThat(jdbc.queryForObject("select count(*) from resident_photo where resident_id = ?", Long.class, zed)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from privacy_consent where resident_id = ? and revoked_at is null",
                Long.class, zed)).isZero();
    }
}
