package br.com.domus.platform.commercial;

import br.com.domus.platform.AbstractIntegrationTest;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.domus.platform.identity.entity.AppUser;
import br.com.domus.platform.identity.entity.Membership;
import br.com.domus.platform.identity.entity.Role;
import br.com.domus.platform.identity.repository.AppUserRepository;
import br.com.domus.platform.identity.repository.MembershipRepository;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/** Como em produção (CNPJ válido obrigatório): 14 dígitos com verificadores corretos; pontuação é removida. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestPropertySource(properties = "portaria.commercial.require-valid-cnpj=true")
class CnpjStrictIT extends AbstractIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired AppUserRepository users;
    @Autowired MembershipRepository memberships;

    @BeforeAll
    void seed() {
        AppUser u = users.save(new AppUser("cnpj-platform", "cnpj-platform@example.invalid", "p"));
        memberships.save(new Membership(u.getId(), Role.PLATFORM_ADMIN, null, null));
    }

    private static RequestPostProcessor platform() {
        return jwt().jwt(j -> j.subject("cnpj-platform"));
    }

    private org.springframework.test.web.servlet.ResultActions create(String taxId) throws Exception {
        return mvc.perform(post("/api/v1/companies").with(platform()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Empresa " + taxId.hashCode() + "\",\"taxId\":\"" + taxId + "\"}"));
    }

    @Test
    void strictModeAcceptsOnlyValidNumbersAndStoresDigitsOnly() throws Exception {
        create("11.222.333/0001-81").andExpect(status().isCreated()).andExpect(jsonPath("$.taxId").value("11222333000181"));
        create("11.222.333/0001-82").andExpect(status().isBadRequest());
        create("00000000000000").andExpect(status().isBadRequest());
        create("12345").andExpect(status().isBadRequest());
        create("fake-company-id").andExpect(status().isBadRequest());
        // o mesmo CNPJ escrito de outro jeito é a mesma empresa
        create("11222333000181").andExpect(status().isConflict());
    }
}
