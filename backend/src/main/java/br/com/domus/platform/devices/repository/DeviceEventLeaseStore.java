package br.com.domus.platform.devices.repository;

import java.time.Duration;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Posse (lease) da conexão de eventos de cada dispositivo, para que só uma instância da aplicação a mantenha. Cada operação é
 * uma instrução curta, sem transação aberta nem conexão retida, e o prazo é medido pelo relógio do banco.
 */
@Repository
public class DeviceEventLeaseStore {

    private final JdbcClient jdbc;

    /** Construtor de DeviceEventLeaseStore; recebe as dependências por injeção. */
    public DeviceEventLeaseStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Assume ou renova a posse.
     *
     * @return verdadeiro se a instância {@code owner} é a dona agora (posse nova, renovada ou vencida de outra instância)
     */
    public boolean acquireOrRenew(UUID deviceId, String owner, Duration ttl) {
        return jdbc.sql("""
                INSERT INTO device_event_lease (device_id, owner, expires_at)
                VALUES (:device, :owner, now() + make_interval(secs => :secs))
                ON CONFLICT (device_id) DO UPDATE
                  SET owner = EXCLUDED.owner, expires_at = EXCLUDED.expires_at
                  WHERE device_event_lease.owner = EXCLUDED.owner OR device_event_lease.expires_at < now()
                """).param("device", deviceId).param("owner", owner).param("secs", (double) ttl.toSeconds()).update() == 1;
    }

    /** Devolve a posse (parada ordenada), para outra instância assumir sem esperar o prazo. */
    public void release(UUID deviceId, String owner) {
        jdbc.sql("DELETE FROM device_event_lease WHERE device_id = :device AND owner = :owner")
                .param("device", deviceId).param("owner", owner).update();
    }

    /** Devolve todas as posses da instância. */
    public void releaseAll(String owner) {
        jdbc.sql("DELETE FROM device_event_lease WHERE owner = :owner").param("owner", owner).update();
    }
}
