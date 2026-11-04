package br.com.domus.platform.identity;

import br.com.domus.platform.AbstractIntegrationTest;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.domus.platform.commercial.entity.Condominium;
import br.com.domus.platform.commercial.entity.OperatorCompany;
import br.com.domus.platform.commercial.repository.CondominiumRepository;
import br.com.domus.platform.commercial.repository.OperatorCompanyRepository;
import br.com.domus.platform.identity.entity.AppUser;
import br.com.domus.platform.identity.entity.Membership;
import br.com.domus.platform.identity.entity.Role;
import br.com.domus.platform.identity.repository.AppUserRepository;
import br.com.domus.platform.identity.repository.MembershipRepository;
import br.com.domus.platform.identity.service.IdentityProvisioner;
import br.com.domus.platform.shared.crypto.FieldCipher;
import br.com.domus.platform.shared.error.ConflictException;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * Criação da conta da equipe pelo provedor de identidade (provedor simulado aqui; o Keycloak real é provado por
 * {@code scripts/verify_keycloak.py}). A autorização vem antes de qualquer chamada ao provedor e uma falha local desfaz a conta.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AccountProvisioningIT extends AbstractIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired OperatorCompanyRepository companies;
    @Autowired CondominiumRepository condominiums;
    @Autowired AppUserRepository users;
    @Autowired MembershipRepository memberships;
    @Autowired FieldCipher cipher;
    @MockitoBean IdentityProvisioner idp;

    UUID companyA, companyB, condoA;

    @BeforeAll
    void seed() {
        companyA = companies.save(new OperatorCompany("Prov A", "prov-a-000000000")).getId();
        companyB = companies.save(new OperatorCompany("Prov B", "prov-b-000000000")).getId();
        condoA = condominiums.save(new Condominium(companyA, "Prov CondoA", null)).getId();
        user("prov-platform", Role.PLATFORM_ADMIN, null, null);
        user("prov-admin-a", Role.COMPANY_ADMIN, companyA, null);
        user("prov-admin-b", Role.COMPANY_ADMIN, companyB, null);
        user("prov-manager-a", Role.CONDO_MANAGER, null, condoA);
    }

    private void user(String subject, Role role, UUID company, UUID condo) {
        AppUser u = users.save(new AppUser(subject, subject + "@example.invalid", subject));
        memberships.save(new Membership(u.getId(), role, company, condo));
    }

    private static RequestPostProcessor as(String subject) {
        return jwt().jwt(j -> j.subject(subject));
    }

    private static String body(String role, String email) {
        return "{\"role\":\"" + role + "\",\"account\":{\"email\":\"" + email + "\",\"displayName\":\"Pessoa Teste\"}}";
    }

    @Test
    void companyAdminCreatesAnOperatorAndTheSubjectIsLinkedWithoutCopyingIt() throws Exception {
        when(idp.create(eq("novo.operador@example.invalid"), any(), eq("operator")))
                .thenReturn(new IdentityProvisioner.Created("kc-sub-op-1", "Tmp-Pass-1234567890"));
        mvc.perform(post("/api/v1/condominiums/" + condoA + "/members/provision").with(as("prov-admin-a"))
                        .contentType(MediaType.APPLICATION_JSON).content(body("OPERATOR", "novo.operador@example.invalid")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.temporaryPassword").value("Tmp-Pass-1234567890"))
                .andExpect(jsonPath("$.membership.role").value("OPERATOR"));
        AppUser created = users.findByExternalSubject("kc-sub-op-1").orElseThrow();
        assertThat(memberships.existsByUserIdAndRoleAndCondominiumIdAndActiveTrue(created.getId(), Role.OPERATOR, condoA)).isTrue();
        // a pessoa nova já entra pelo vínculo criado
        mvc.perform(get("/api/v1/operator/overview").with(as("kc-sub-op-1"))).andExpect(status().isOk());
    }

    @Test
    void managerRoleMapsToTheCondoManagerRoleInTheProvider() throws Exception {
        when(idp.create(eq("sindico.novo@example.invalid"), any(), eq("condo-manager")))
                .thenReturn(new IdentityProvisioner.Created("kc-sub-mgr-1", "Tmp-Pass-1234567890"));
        mvc.perform(post("/api/v1/condominiums/" + condoA + "/members/provision").with(as("prov-admin-a"))
                        .contentType(MediaType.APPLICATION_JSON).content(body("CONDO_MANAGER", "sindico.novo@example.invalid")))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.membership.role").value("CONDO_MANAGER"));
    }

    @Test
    void unauthorizedCallersNeverReachTheProvider() throws Exception {
        for (String who : new String[] {"prov-admin-b", "prov-manager-a"}) {
            mvc.perform(post("/api/v1/condominiums/" + condoA + "/members/provision").with(as(who))
                            .contentType(MediaType.APPLICATION_JSON).content(body("OPERATOR", "intruso@example.invalid")))
                    .andExpect(status().is4xxClientError());
        }
        mvc.perform(post("/api/v1/companies/" + companyA + "/admins/provision").with(as("prov-admin-a"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"email\":\"x@example.invalid\",\"displayName\":\"X\"}"))
                .andExpect(status().is4xxClientError());
        verify(idp, never()).create(eq("intruso@example.invalid"), any(), any());
        verify(idp, never()).create(eq("x@example.invalid"), any(), any());
    }

    @Test
    void accountThatAlreadyExistsInTheProviderIsAConflict() throws Exception {
        when(idp.create(eq("ja.existe@example.invalid"), any(), any()))
                .thenThrow(new ConflictException("An account with this e-mail already exists in the identity provider"));
        mvc.perform(post("/api/v1/condominiums/" + condoA + "/members/provision").with(as("prov-admin-a"))
                        .contentType(MediaType.APPLICATION_JSON).content(body("OPERATOR", "ja.existe@example.invalid")))
                .andExpect(status().isConflict());
    }

    @Test
    void localFailureUndoesTheAccountCreatedInTheProvider() throws Exception {
        // o e-mail já pertence a outra conta local: o vínculo falha depois de a conta ter sido criada no provedor
        users.save(new AppUser("kc-other", "duplicado@example.invalid", "Outro", cipher.emailIndex("duplicado@example.invalid")));
        when(idp.create(eq("duplicado@example.invalid"), any(), eq("operator")))
                .thenReturn(new IdentityProvisioner.Created("kc-sub-dup", "Tmp-Pass-1234567890"));
        mvc.perform(post("/api/v1/condominiums/" + condoA + "/members/provision").with(as("prov-admin-a"))
                        .contentType(MediaType.APPLICATION_JSON).content(body("OPERATOR", "duplicado@example.invalid")))
                .andExpect(status().is4xxClientError());
        verify(idp).delete("kc-sub-dup");
    }

    @Test
    void platformCreatesACompanyAdminAndStatusReportsEnabled() throws Exception {
        when(idp.create(eq("admin.empresa@example.invalid"), any(), eq("company-admin")))
                .thenReturn(new IdentityProvisioner.Created("kc-sub-ca-1", "Tmp-Pass-1234567890"));
        mvc.perform(post("/api/v1/companies/" + companyB + "/admins/provision").with(as("prov-platform"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"admin.empresa@example.invalid\",\"displayName\":\"Admin Empresa\"}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.membership.role").value("COMPANY_ADMIN"));
        mvc.perform(get("/api/v1/identity/provisioning").with(as("prov-platform"))).andExpect(jsonPath("$.enabled").value(true));
    }

    private UUID provisionOperator(String email, String subject) throws Exception {
        when(idp.create(eq(email), any(), eq("operator"))).thenReturn(new IdentityProvisioner.Created(subject, "Tmp-Pass-1234567890"));
        String json = mvc.perform(post("/api/v1/condominiums/" + condoA + "/members/provision").with(as("prov-admin-a"))
                        .contentType(MediaType.APPLICATION_JSON).content(body("OPERATOR", email)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return UUID.fromString(new tools.jackson.databind.ObjectMapper().readTree(json).get("membership").get("id").asString());
    }

    @Test
    void lostTemporaryPasswordCanBeReplacedForAnAccountThePlatformCreated() throws Exception {
        UUID membership = provisionOperator("perdeu.senha@example.invalid", "kc-sub-reset-1");
        when(idp.resetPassword("kc-sub-reset-1")).thenReturn("Nova-Tmp-123456789");
        mvc.perform(post("/api/v1/condominiums/" + condoA + "/members/" + membership + "/reset-password").with(as("prov-admin-a")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.temporaryPassword").value("Nova-Tmp-123456789"));
        // quem não administra o condomínio nunca chega ao provedor
        for (String who : new String[] {"prov-admin-b", "prov-manager-a"}) {
            mvc.perform(post("/api/v1/condominiums/" + condoA + "/members/" + membership + "/reset-password").with(as(who)))
                    .andExpect(status().is4xxClientError());
        }
        verify(idp, org.mockito.Mockito.times(1)).resetPassword("kc-sub-reset-1");
    }

    @Test
    void passwordResetIsRefusedForAccountsNotCreatedByThePlatformOrWithAccessElsewhere() throws Exception {
        // conta ligada à mão: a plataforma não a criou e não mexe na senha
        user("prov-manual-op", Role.OPERATOR, null, condoA);
        UUID manual = memberships.findByUserIdAndActiveTrue(users.findByExternalSubject("prov-manual-op").orElseThrow().getId())
                .get(0).getId();
        mvc.perform(post("/api/v1/condominiums/" + condoA + "/members/" + manual + "/reset-password").with(as("prov-admin-a")))
                .andExpect(status().isConflict());
        // conta criada pela plataforma, mas que também opera em OUTRA empresa: o administrador da primeira não a controla
        UUID membership = provisionOperator("dois.lugares@example.invalid", "kc-sub-two-places");
        UUID condoB = condominiums.save(new Condominium(companyB, "Prov CondoB", null)).getId();
        UUID userId = users.findByExternalSubject("kc-sub-two-places").orElseThrow().getId();
        memberships.save(new Membership(userId, Role.OPERATOR, null, condoB));
        mvc.perform(post("/api/v1/condominiums/" + condoA + "/members/" + membership + "/reset-password").with(as("prov-admin-a")))
                .andExpect(status().isConflict());
        verify(idp, never()).resetPassword("kc-sub-two-places");
        verify(idp, never()).resetPassword("prov-manual-op");
    }

    @Test
    void revokingTheLastMembershipOfAPlatformCreatedAccountDeletesItSoTheEmailCanBeUsedAgain() throws Exception {
        UUID membership = provisionOperator("volta.depois@example.invalid", "kc-sub-back-1");
        mvc.perform(post("/api/v1/condominiums/" + condoA + "/members/" + membership + "/revoke").with(as("prov-admin-a")))
                .andExpect(status().isNoContent());
        verify(idp).delete("kc-sub-back-1");
        // o mesmo e-mail pode ser cadastrado de novo (a conta antiga saiu do provedor e o e-mail foi liberado localmente)
        provisionOperator("volta.depois@example.invalid", "kc-sub-back-2");
        assertThat(users.findByExternalSubject("kc-sub-back-2")).isPresent();
    }

    @Test
    void revokingAManuallyLinkedAccountNeverDeletesAnythingInTheProvider() throws Exception {
        user("prov-manual-2", Role.OPERATOR, null, condoA);
        UUID manual = memberships.findByUserIdAndActiveTrue(users.findByExternalSubject("prov-manual-2").orElseThrow().getId())
                .get(0).getId();
        mvc.perform(post("/api/v1/condominiums/" + condoA + "/members/" + manual + "/revoke").with(as("prov-admin-a")))
                .andExpect(status().isNoContent());
        verify(idp, never()).delete("prov-manual-2");
    }
}
