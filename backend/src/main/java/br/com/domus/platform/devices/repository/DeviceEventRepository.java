package br.com.domus.platform.devices.repository;

import br.com.domus.platform.devices.entity.DeviceEvent;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Acesso a eventos de dispositivos. */
public interface DeviceEventRepository extends JpaRepository<DeviceEvent, UUID> {

    long countByDeviceIdAndType(UUID deviceId, String type);
}
