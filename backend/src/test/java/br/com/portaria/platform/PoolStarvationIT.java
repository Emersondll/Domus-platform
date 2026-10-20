package br.com.portaria.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import br.com.portaria.platform.commercial.Block;
import br.com.portaria.platform.commercial.BlockRepository;
import br.com.portaria.platform.commercial.Condominium;
import br.com.portaria.platform.commercial.CondominiumRepository;
import br.com.portaria.platform.commercial.OperatorCompany;
import br.com.portaria.platform.commercial.OperatorCompanyRepository;
import br.com.portaria.platform.commercial.Unit;
import br.com.portaria.platform.commercial.UnitRepository;
import br.com.portaria.platform.identity.AppUser;
import br.com.portaria.platform.identity.AppUserRepository;
import br.com.portaria.platform.identity.Membership;
import br.com.portaria.platform.identity.MembershipRepository;
import br.com.portaria.platform.identity.Role;
import br.com.portaria.platform.residents.Resident;
import br.com.portaria.platform.residents.ResidentRepository;
import br.com.portaria.platform.residents.ResidentType;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Regressão (achada no teste de carga): a auditoria abria uma segunda conexão (REQUIRES_NEW) dentro da transação da
 * requisição. Com concorrência >= tamanho do pool, todas as conexões ficavam presas esperando outra, e a API travava
 * até o timeout do pool. Aqui o pool tem 3 conexões e disparamos 12 requisições auditadas ao mesmo tempo.
 */
@SpringBootTest(properties = {
        "portaria.rate-limit.api-per-minute=100000",
        "spring.datasource.hikari.maximum-pool-size=3", "spring.datasource.hikari.connection-timeout=4000"})
class PoolStarvationIT extends AbstractIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired OperatorCompanyRepository companies;
    @Autowired CondominiumRepository condominiums;
    @Autowired BlockRepository blocks;
    @Autowired UnitRepository units;
    @Autowired ResidentRepository residents;
    @Autowired AppUserRepository users;
    @Autowired MembershipRepository memberships;

    @Test
    void auditedEndpointsDoNotStarveTheConnectionPoolUnderConcurrency() throws Exception {
        UUID company = companies.save(new OperatorCompany("Pool Co", "pool-0000000000")).getId();
        UUID condo = condominiums.save(new Condominium(company, "Pool Condo", null)).getId();
        UUID block = blocks.save(new Block(condo, "A")).getId();
        UUID unit = units.save(new Unit(condo, block, "101")).getId();
        residents.save(new Resident(condo, unit, "Pool Morador", null, null, ResidentType.OWNER));
        AppUser u = users.save(new AppUser("pool-manager", "pool-manager@example.invalid", "pool"));
        memberships.save(new Membership(u.getId(), Role.CONDO_MANAGER, null, condo));
        // um usuário de outro condomínio gera NEGAÇÕES auditadas em paralelo (outro caminho de auditoria)
        AppUser stranger = users.save(new AppUser("pool-stranger", "pool-stranger@example.invalid", "stranger"));
        UUID otherCondo = condominiums.save(new Condominium(company, "Pool Other", null)).getId();
        memberships.save(new Membership(stranger.getId(), Role.CONDO_MANAGER, null, otherCondo));

        int n = 12;
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> futures = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            String who = i % 2 == 0 ? "pool-manager" : "pool-stranger";
            Callable<Integer> call = () -> {
                start.await();
                return mvc.perform(get("/api/v1/condominiums/" + condo + "/residents")
                        .with(jwt().jwt(j -> j.subject(who)))).andReturn().getResponse().getStatus();
            };
            futures.add(pool.submit(call));
        }
        long t0 = System.nanoTime();
        start.countDown();
        List<Integer> statuses = new ArrayList<>();
        for (Future<Integer> f : futures) {
            statuses.add(f.get());
        }
        pool.shutdown();
        long seconds = (System.nanoTime() - t0) / 1_000_000_000;

        assertThat(statuses).filteredOn(s -> s == 200).hasSize(n / 2);
        assertThat(statuses).filteredOn(s -> s == 404).hasSize(n / 2);
        assertThat(seconds).as("nenhuma requisição deve esperar o timeout do pool").isLessThan(4);
    }
}
