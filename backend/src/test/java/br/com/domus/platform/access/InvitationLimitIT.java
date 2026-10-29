package br.com.domus.platform.access;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/** Teto de convites ativos por unidade (30, decisão do titular): sequencial e com pedidos simultâneos. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class InvitationLimitIT extends AbstractIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired OperatorCompanyRepository companies;
    @Autowired CondominiumRepository condominiums;
    @Autowired BlockRepository blocks;
    @Autowired UnitRepository units;
    @Autowired ResidentRepository residents;
    @Autowired AppUserRepository users;
    @Autowired MembershipRepository memberships;

    UUID condo;

    @BeforeAll
    void seed() {
        OperatorCompany c = companies.save(new OperatorCompany("Limit Company", "limit-0000000000"));
        condo = condominiums.save(new Condominium(c.getId(), "Limit Main", null)).getId();
        Block b = blocks.save(new Block(condo, "A"));
        for (String[] row : new String[][] {{"101", "lim-a", "lim-b"}, {"102", "lim-c", null}, {"103", "lim-d", null}}) {
            UUID unit = units.save(new Unit(condo, b.getId(), row[0])).getId();
            for (int i = 1; i < row.length; i++) {
                if (row[i] == null) {
                    continue;
                }
                Resident r = residents.save(new Resident(condo, unit, row[i], null, null, ResidentType.OWNER));
                AppUser u = users.save(new AppUser(row[i], row[i] + "@example.invalid", row[i]));
                memberships.save(Membership.forResident(u.getId(), condo, r.getId()));
            }
        }
    }

    private static RequestPostProcessor as(String s) {
        return jwt().jwt(j -> j.subject(s));
    }

    private int invite(String subject, int maxUses) throws Exception {
        java.time.Instant now = java.time.Instant.now();
        return mvc.perform(post("/api/v1/condominiums/" + condo + "/me/invitations").with(as(subject))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"visitorName\":\"V\",\"kind\":\"VISITOR\",\"validFrom\":\"" + now.minusSeconds(30)
                        + "\",\"validUntil\":\"" + now.plusSeconds(600) + "\",\"maxUses\":" + maxUses + "}"))
                .andReturn().getResponse().getStatus();
    }

    @Test
    void the31stActiveInvitationOfAUnitIsRefusedAndOtherUnitsAreUnaffected() throws Exception {
        for (int i = 0; i < 30; i++) {
            assertThat(invite("lim-c", 1)).isEqualTo(201);
        }
        assertThat(invite("lim-c", 1)).isEqualTo(409);
        assertThat(invite("lim-d", 1)).isEqualTo(201);
    }

    @Test
    void simultaneousRequestsFromTheSameUnitNeverExceedTheLimit() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(40);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Integer>> futures = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            String who = i % 2 == 0 ? "lim-a" : "lim-b"; // dois moradores da mesma unidade
            futures.add(pool.submit(() -> {
                go.await();
                return invite(who, 1);
            }));
        }
        go.countDown();
        int created = 0;
        for (Future<Integer> f : futures) {
            created += f.get() == 201 ? 1 : 0;
        }
        pool.shutdown();
        assertThat(created).isEqualTo(30);
        assertThat(jdbc.sql("SELECT count(*) FROM access_credential c JOIN unit u ON u.id = c.unit_id "
                + "WHERE c.condominium_id = :c AND u.number = '101'").param("c", condo).query(Integer.class).single())
                .isEqualTo(30);
    }
}
