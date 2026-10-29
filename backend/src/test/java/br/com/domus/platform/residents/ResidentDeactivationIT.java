package br.com.domus.platform.residents;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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
import br.com.domus.platform.identity.entity.AppUser;
import br.com.domus.platform.identity.entity.Membership;
import br.com.domus.platform.identity.entity.Role;
import br.com.domus.platform.identity.repository.AppUserRepository;
import br.com.domus.platform.identity.repository.MembershipRepository;
import br.com.domus.platform.residents.entity.Resident;
import br.com.domus.platform.residents.entity.ResidentType;
import br.com.domus.platform.residents.repository.ResidentRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/** Quem tem o cadastro desativado (mudou-se) perde o acesso de morador; os demais da unidade continuam normais. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ResidentDeactivationIT extends AbstractIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired OperatorCompanyRepository companies;
    @Autowired CondominiumRepository condominiums;
    @Autowired BlockRepository blocks;
    @Autowired UnitRepository units;
    @Autowired ResidentRepository residents;
    @Autowired AppUserRepository users;
    @Autowired MembershipRepository memberships;

    UUID condo, leavingId;

    @BeforeAll
    void seed() {
        OperatorCompany c = companies.save(new OperatorCompany("Deact Company", "deact-0000000000"));
        condo = condominiums.save(new Condominium(c.getId(), "Deact Main", null)).getId();
        Block b = blocks.save(new Block(condo, "A"));
        UUID unit = units.save(new Unit(condo, b.getId(), "101")).getId();
        leavingId = resident("deact-leaving", unit, ResidentType.TENANT);
        resident("deact-staying", unit, ResidentType.OWNER);
        AppUser mgr = users.save(new AppUser("deact-manager", "deact-manager@example.invalid", "m"));
        memberships.save(new Membership(mgr.getId(), Role.CONDO_MANAGER, null, condo));
    }

    private UUID resident(String subject, UUID unit, ResidentType type) {
        Resident r = residents.save(new Resident(condo, unit, subject, null, null, type));
        AppUser u = users.save(new AppUser(subject, subject + "@example.invalid", subject));
        memberships.save(Membership.forResident(u.getId(), condo, r.getId()));
        return r.getId();
    }

    private static RequestPostProcessor as(String s) {
        return jwt().jwt(j -> j.subject(s));
    }

    private String base() {
        return "/api/v1/condominiums/" + condo;
    }

    private String invitation() {
        Instant now = Instant.now();
        return "{\"visitorName\":\"Visita\",\"kind\":\"VISITOR\",\"validFrom\":\"" + now.minusSeconds(30)
                + "\",\"validUntil\":\"" + now.plus(1, ChronoUnit.HOURS) + "\",\"maxUses\":1}";
    }

    @Test
    void deactivatedResidentLosesResidentAccessButTheRestOfTheUnitKeepsIt() throws Exception {
        mvc.perform(post(base() + "/me/invitations").with(as("deact-leaving")).contentType(MediaType.APPLICATION_JSON)
                .content(invitation())).andExpect(status().isCreated());

        mvc.perform(put(base() + "/residents/" + leavingId).with(as("deact-manager")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"fullName\":\"deact-leaving\",\"residentType\":\"TENANT\",\"active\":false}"))
                .andExpect(status().isOk());

        // vínculo encerrado: para ele o condomínio deixa de existir (404), em leitura e em escrita
        mvc.perform(post(base() + "/me/invitations").with(as("deact-leaving")).contentType(MediaType.APPLICATION_JSON)
                .content(invitation())).andExpect(status().isNotFound());
        mvc.perform(post(base() + "/me/calls").with(as("deact-leaving")).contentType(MediaType.APPLICATION_JSON)
                .content("{}")).andExpect(status().isNotFound());
        mvc.perform(post(base() + "/me/panic").with(as("deact-leaving")).contentType(MediaType.APPLICATION_JSON)
                .content("{}")).andExpect(status().isNotFound());
        mvc.perform(get(base() + "/me/cameras").with(as("deact-leaving"))).andExpect(status().isNotFound());
        assertThat(memberships.findByResidentId(leavingId)).noneMatch(Membership::isActive);

        // quem mora na mesma unidade não é afetado
        mvc.perform(post(base() + "/me/invitations").with(as("deact-staying")).contentType(MediaType.APPLICATION_JSON)
                .content(invitation())).andExpect(status().isCreated());
    }

    @Test
    void updatingAnActiveResidentWithoutDeactivatingKeepsTheAccess() throws Exception {
        UUID unit = residents.findById(leavingId).orElseThrow().getUnitId();
        UUID other = resident("deact-edited", unit, ResidentType.OWNER);
        mvc.perform(put(base() + "/residents/" + other).with(as("deact-manager")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"fullName\":\"deact-edited novo nome\",\"residentType\":\"OWNER\",\"active\":true}"))
                .andExpect(status().isOk());
        mvc.perform(get(base() + "/me/cameras").with(as("deact-edited"))).andExpect(status().isOk());
    }
}
