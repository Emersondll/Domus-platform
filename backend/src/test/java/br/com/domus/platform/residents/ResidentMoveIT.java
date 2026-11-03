package br.com.domus.platform.residents;

import br.com.domus.platform.AbstractIntegrationTest;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/** Mudar um morador de unidade: o que acompanha, o que fica, quem pode e o que a auditoria guarda. Dados sintéticos. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ResidentMoveIT extends AbstractIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired OperatorCompanyRepository companies;
    @Autowired CondominiumRepository condominiums;
    @Autowired BlockRepository blocks;
    @Autowired UnitRepository units;
    @Autowired ResidentRepository residents;
    @Autowired AppUserRepository users;
    @Autowired MembershipRepository memberships;

    @Autowired br.com.domus.platform.residents.repository.PetRepository petRepo;

    UUID condo, otherCondo, u1, u2, u3, u4, foreignUnit;

    @BeforeAll
    void seed() {
        OperatorCompany c = companies.save(new OperatorCompany("Mov Company", "mov-00000000000"));
        condo = condominiums.save(new Condominium(c.getId(), "Mov Main", null)).getId();
        otherCondo = condominiums.save(new Condominium(c.getId(), "Mov Other", null)).getId();
        Block b = blocks.save(new Block(condo, "A"));
        u1 = units.save(new Unit(condo, b.getId(), "101")).getId();
        u2 = units.save(new Unit(condo, b.getId(), "102")).getId();
        u3 = units.save(new Unit(condo, b.getId(), "103")).getId();
        u4 = units.save(new Unit(condo, b.getId(), "104")).getId();
        Block ob = blocks.save(new Block(otherCondo, "A"));
        foreignUnit = units.save(new Unit(otherCondo, ob.getId(), "901")).getId();
        staff("mov-manager", Role.CONDO_MANAGER, condo);
        staff("mov-operator", Role.OPERATOR, condo);
        staff("mov-manager-other", Role.CONDO_MANAGER, otherCondo);
    }

    private void staff(String subject, Role role, UUID c) {
        AppUser u = users.save(new AppUser(subject, subject + "@example.invalid", subject));
        memberships.save(new Membership(u.getId(), role, null, c));
    }

    private UUID resident(String subject, UUID unit, ResidentType type, boolean withAccount) {
        Resident r = residents.save(new Resident(condo, unit, subject, null, null, type));
        if (withAccount) {
            AppUser u = users.save(new AppUser(subject, subject + "@example.invalid", subject));
            memberships.save(Membership.forResident(u.getId(), condo, r.getId()));
        }
        return r.getId();
    }

    private void deactivate(UUID residentId, String name, ResidentType type) {
        Resident r = residents.findById(residentId).orElseThrow();
        r.update(name, null, null, type, false);
        residents.saveAndFlush(r);
    }

    private static RequestPostProcessor as(String s) {
        return jwt().jwt(j -> j.subject(s));
    }

    private String base() {
        return "/api/v1/condominiums/" + condo;
    }

    private ResultActions move(String who, UUID residentId, UUID toUnit, String reason, boolean dependents) throws Exception {
        return mvc.perform(post(base() + "/residents/" + residentId + "/move").with(as(who))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"unitId\":\"" + toUnit + "\",\"reason\":\"" + reason + "\",\"moveDependents\":" + dependents + "}"));
    }

    private ResultActions moveWith(String who, UUID residentId, UUID toUnit, boolean dependents, String vehicleIds, String petIds)
            throws Exception {
        return mvc.perform(post(base() + "/residents/" + residentId + "/move").with(as(who))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"unitId\":\"" + toUnit + "\",\"reason\":\"mudança de apartamento\",\"moveDependents\":" + dependents
                        + ",\"vehicleIds\":" + vehicleIds + ",\"petIds\":" + petIds + "}"));
    }

    private void invite(String who) throws Exception {
        Instant now = Instant.now();
        mvc.perform(post(base() + "/me/invitations").with(as(who)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"visitorName\":\"Visita\",\"kind\":\"VISITOR\",\"validFrom\":\"" + now.minusSeconds(30)
                        + "\",\"validUntil\":\"" + now.plus(1, ChronoUnit.HOURS) + "\",\"maxUses\":1}"))
                .andExpect(status().isCreated());
    }

    private void vehicle(String who, String plate) throws Exception {
        mvc.perform(post(base() + "/me/vehicles").with(as(who)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"plate\":\"" + plate + "\",\"model\":\"Teste\",\"color\":\"Azul\"}")).andExpect(status().isCreated());
    }

    @Test
    void onlyManagementMovesAndNothingCrossesCondominiums() throws Exception {
        UUID r = resident("mov-guard", u1, ResidentType.OWNER, false);
        move("mov-operator", r, u2, "mudança de apartamento", false).andExpect(status().isForbidden());
        move("mov-manager-other", r, u2, "mudança de apartamento", false).andExpect(status().isNotFound());
        move("mov-manager", r, u1, "mudança de apartamento", false).andExpect(status().isConflict()); // mesma unidade
        move("mov-manager", r, foreignUnit, "mudança de apartamento", false).andExpect(status().isNotFound());
        move("mov-manager", r, u2, "ab", false).andExpect(status().isBadRequest()); // motivo curto demais
        move("mov-manager", UUID.randomUUID(), u2, "mudança de apartamento", false).andExpect(status().isNotFound());
        assertThat(residents.findById(r).orElseThrow().getUnitId()).isEqualTo(u1);
    }

    @Test
    void theResidentMovesWithTheAccountWhileVehiclesStayAndTheirInvitationsAreRevoked() throws Exception {
        UUID mover = resident("mov-mover", u1, ResidentType.TENANT, true);
        resident("mov-stays", u1, ResidentType.OWNER, true);
        UUID dependent = resident("mov-child", u1, ResidentType.DEPENDENT, false);
        vehicle("mov-mover", "MOV1A11");
        invite("mov-mover");
        invite("mov-stays"); // convite de quem fica não é tocado
        mvc.perform(get(base() + "/me/invitations").with(as("mov-mover"))).andExpect(jsonPath("$.totalElements").value(2));

        move("mov-manager", mover, u2, "mudança de apartamento", false).andExpect(status().isOk())
                .andExpect(jsonPath("$.resident.unitId").value(u2.toString()))
                .andExpect(jsonPath("$.dependentsMoved").value(0))
                .andExpect(jsonPath("$.invitationsRevoked").value(1));

        // o acesso ao app continua e agora enxerga a unidade nova: sem veículos e sem os convites da unidade antiga
        mvc.perform(get(base() + "/me/vehicles").with(as("mov-mover"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));
        mvc.perform(get(base() + "/me/invitations").with(as("mov-mover"))).andExpect(jsonPath("$.totalElements").value(0));
        // na unidade antiga o veículo e o dependente ficaram, e o convite de quem ficou segue ativo
        mvc.perform(get(base() + "/me/vehicles").with(as("mov-stays"))).andExpect(jsonPath("$.totalElements").value(1));
        assertThat(jdbc.queryForObject("select count(*) from access_credential where unit_id = ? and status = 'ACTIVE'",
                Integer.class, u1)).isEqualTo(1);
        assertThat(residents.findById(dependent).orElseThrow().getUnitId()).isEqualTo(u1);
        // o convite revogado aparece como revogado na unidade antiga
        assertThat(jdbc.queryForObject("select count(*) from access_credential where unit_id = ? and status = 'REVOKED'",
                Integer.class, u1)).isEqualTo(1);

        List<String> audit = jdbc.queryForList("select detail from audit_log where action = 'resident.move' and resource_id = ?",
                String.class, mover.toString());
        assertThat(audit).singleElement().asString().contains(u1.toString()).contains(u2.toString())
                .contains("invitations revoked: 1").contains("mudança de apartamento");
    }

    @Test
    void dependentsOfTheOldUnitGoAlongOnlyWhenAsked() throws Exception {
        UUID adult = resident("mov-parent", u2, ResidentType.OWNER, true);
        UUID kid = resident("mov-kid", u2, ResidentType.DEPENDENT, false);
        UUID inactiveKid = resident("mov-old-kid", u2, ResidentType.DEPENDENT, false);
        deactivate(inactiveKid, "mov-old-kid", ResidentType.DEPENDENT);

        move("mov-manager", adult, u3, "mudança de apartamento", true).andExpect(status().isOk())
                .andExpect(jsonPath("$.dependentsMoved").value(1));
        assertThat(residents.findById(kid).orElseThrow().getUnitId()).isEqualTo(u3);
        assertThat(residents.findById(inactiveKid).orElseThrow().getUnitId()).isEqualTo(u2); // inativo não vai
    }

    @Test
    void anInactiveResidentCannotBeMoved() throws Exception {
        UUID r = resident("mov-gone", u3, ResidentType.OWNER, false);
        deactivate(r, "mov-gone", ResidentType.OWNER);
        move("mov-manager", r, u1, "mudança de apartamento", false).andExpect(status().isConflict());
    }

    @Test
    void onlyTheChosenVehiclesAndPetsGoAlongAndAWrongIdMovesNothing() throws Exception {
        UUID mover = resident("mov-owner-vp", u4, ResidentType.OWNER, true);
        vehicle("mov-owner-vp", "VPA1B22");
        vehicle("mov-owner-vp", "VPC3D44");
        List<String> ids = jdbc.queryForList("select id::text from vehicle where unit_id = ? order by created_at", String.class, u4);
        var pet1 = petRepo.save(new br.com.domus.platform.residents.entity.Pet(condo, u4, "Rex", "DOG", null, null, Instant.now()));
        var pet2 = petRepo.save(new br.com.domus.platform.residents.entity.Pet(condo, u4, "Mimi", "CAT", null, null, Instant.now()));
        UUID foreignVehicleUnit = u1;
        // um veículo de outra unidade na lista: erro e nada se move
        vehicle("mov-stays", "ZZZ9Z99");
        String foreign = jdbc.queryForList("select id::text from vehicle where unit_id = ? order by created_at desc", String.class,
                foreignVehicleUnit).get(0);
        moveWith("mov-manager", mover, u3, false, "[\"" + ids.get(0) + "\",\"" + foreign + "\"]", "[]").andExpect(status().isNotFound());
        assertThat(jdbc.queryForObject("select count(*) from vehicle where unit_id = ?", Integer.class, u4)).isEqualTo(2);
        assertThat(residents.findById(mover).orElseThrow().getUnitId()).isEqualTo(u4);

        moveWith("mov-manager", mover, u3, false, "[\"" + ids.get(0) + "\"]", "[\"" + pet1.getId() + "\"]")
                .andExpect(status().isOk()).andExpect(jsonPath("$.vehiclesMoved").value(1)).andExpect(jsonPath("$.petsMoved").value(1));
        assertThat(jdbc.queryForObject("select unit_id::text from vehicle where id = ?::uuid", String.class, ids.get(0))).isEqualTo(u3.toString());
        assertThat(jdbc.queryForObject("select unit_id::text from vehicle where id = ?::uuid", String.class, ids.get(1))).isEqualTo(u4.toString());
        assertThat(petRepo.findById(pet1.getId()).orElseThrow().getUnitId()).isEqualTo(u3);
        assertThat(petRepo.findById(pet2.getId()).orElseThrow().getUnitId()).isEqualTo(u4);
        assertThat(jdbc.queryForObject("select count(*) from audit_log where action in ('vehicle.move','pet.move') and resource_id in (?, ?)",
                Integer.class, ids.get(0), pet1.getId().toString())).isEqualTo(2);
    }

    @Test
    void dependentsFollowTheirResponsibleAdultAndOthersStayUnlinkedFromTheMover() throws Exception {
        UUID unit = u2;
        UUID adultA = resident("mov-adult-a", unit, ResidentType.OWNER, true);
        UUID adultB = resident("mov-adult-b", unit, ResidentType.SPOUSE, true);
        UUID kidA = resident("mov-kid-a", unit, ResidentType.DEPENDENT, false);
        UUID kidB = resident("mov-kid-b", unit, ResidentType.DEPENDENT, false);
        UUID kidFree = resident("mov-kid-free", unit, ResidentType.DEPENDENT, false);
        // a administração liga cada dependente ao seu adulto; dependente não pode ser responsável nem de outra unidade
        String body = "{\"fullName\":\"%s\",\"residentType\":\"DEPENDENT\",\"active\":true,\"responsibleResidentId\":\"%s\"}";
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(base() + "/residents/" + kidA).with(as("mov-manager"))
                .contentType(MediaType.APPLICATION_JSON).content(body.formatted("mov-kid-a", adultA))).andExpect(status().isOk())
                .andExpect(jsonPath("$.responsibleResidentId").value(adultA.toString()));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(base() + "/residents/" + kidB).with(as("mov-manager"))
                .contentType(MediaType.APPLICATION_JSON).content(body.formatted("mov-kid-b", adultB))).andExpect(status().isOk());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(base() + "/residents/" + kidFree).with(as("mov-manager"))
                .contentType(MediaType.APPLICATION_JSON).content(body.formatted("mov-kid-free", kidA))).andExpect(status().isBadRequest());
        UUID elsewhere = resident("mov-adult-elsewhere", u3, ResidentType.OWNER, false);
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(base() + "/residents/" + kidFree).with(as("mov-manager"))
                .contentType(MediaType.APPLICATION_JSON).content(body.formatted("mov-kid-free", elsewhere))).andExpect(status().isBadRequest());

        moveWith("mov-manager", adultA, u1, true, "[]", "[]").andExpect(status().isOk())
                .andExpect(jsonPath("$.dependentsMoved").value(2)); // o dele e o sem responsável
        assertThat(residents.findById(kidA).orElseThrow().getUnitId()).isEqualTo(u1);
        assertThat(residents.findById(kidFree).orElseThrow().getUnitId()).isEqualTo(u1);
        assertThat(residents.findById(kidB).orElseThrow().getUnitId()).isEqualTo(unit); // do outro adulto: fica
        assertThat(residents.findById(kidB).orElseThrow().getResponsibleResidentId()).isEqualTo(adultB);
    }

    @Test
    void dependentsLeftBehindLoseTheLinkToTheAdultWhoLeft() throws Exception {
        UUID adult = resident("mov-adult-c", u3, ResidentType.OWNER, true);
        UUID kid = resident("mov-kid-c", u3, ResidentType.DEPENDENT, false);
        Resident k = residents.findById(kid).orElseThrow();
        k.linkResponsible(adult);
        residents.saveAndFlush(k);
        move("mov-manager", adult, u4, "mudança de apartamento", false).andExpect(status().isOk());
        Resident after = residents.findById(kid).orElseThrow();
        assertThat(after.getUnitId()).isEqualTo(u3);
        assertThat(after.getResponsibleResidentId()).isNull();
    }
}
