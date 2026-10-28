package br.com.domus.platform.devices;

import br.com.domus.platform.AbstractIntegrationTest;
import static org.assertj.core.api.Assertions.assertThat;

import br.com.domus.platform.commercial.entity.Condominium;
import br.com.domus.platform.commercial.entity.OperatorCompany;
import br.com.domus.platform.commercial.repository.CondominiumRepository;
import br.com.domus.platform.commercial.repository.OperatorCompanyRepository;
import br.com.domus.platform.devices.entity.Device;
import br.com.domus.platform.devices.repository.DeviceEventLeaseStore;
import br.com.domus.platform.devices.repository.DeviceRepository;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Posse da conexão de eventos: uma só instância por dispositivo, com takeover quando a posse vence (B7). */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DeviceEventLeaseIT extends AbstractIntegrationTest {

    @Autowired DeviceEventLeaseStore leases;
    @Autowired JdbcClient jdbc;
    @Autowired OperatorCompanyRepository companies;
    @Autowired CondominiumRepository condominiums;
    @Autowired DeviceRepository devices;

    UUID device;

    @BeforeAll
    void seed() {
        OperatorCompany c = companies.save(new OperatorCompany("Lease Company", "lease-000000000"));
        UUID condo = condominiums.save(new Condominium(c.getId(), "Lease Main", null)).getId();
        device = devices.save(new Device(condo, "Controladora", "ACCESS_CONTROLLER", "DAHUA_CGI", "main", Map.of(), null)).getId();
    }

    @Test
    void onlyOneInstanceOwnsTheDeviceAndOthersTakeOverWhenItExpiresOrReleases() {
        Duration ttl = Duration.ofSeconds(90);
        assertThat(leases.acquireOrRenew(device, "A", ttl)).isTrue();
        assertThat(leases.acquireOrRenew(device, "B", ttl)).isFalse();   // A é a dona
        assertThat(leases.acquireOrRenew(device, "A", ttl)).isTrue();    // renovação

        jdbc.sql("UPDATE device_event_lease SET expires_at = now() - interval '1 second' WHERE device_id = ?").param(device).update();
        assertThat(leases.acquireOrRenew(device, "B", ttl)).isTrue();    // A caiu: B assume
        assertThat(leases.acquireOrRenew(device, "A", ttl)).isFalse();

        leases.release(device, "A");                                      // quem não é dono não derruba a posse
        assertThat(leases.acquireOrRenew(device, "A", ttl)).isFalse();
        leases.release(device, "B");                                      // parada ordenada
        assertThat(leases.acquireOrRenew(device, "A", ttl)).isTrue();
        leases.releaseAll("A");
    }

    @Test
    void concurrentInstancesNeverBothWin() throws Exception {
        var winners = new CopyOnWriteArrayList<String>();
        int n = 12;
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(n)) {
            var futures = new java.util.ArrayList<java.util.concurrent.Future<?>>();
            for (int i = 0; i < n; i++) {
                String owner = "inst-" + i;
                futures.add(pool.submit(() -> {
                    start.await();
                    if (leases.acquireOrRenew(device, owner, Duration.ofSeconds(90))) {
                        winners.add(owner);
                    }
                    return null;
                }));
            }
            start.countDown();
            for (var f : futures) {
                f.get();
            }
        }
        assertThat(winners).hasSize(1);
        jdbc.sql("DELETE FROM device_event_lease WHERE device_id = ?").param(device).update();
    }
}
