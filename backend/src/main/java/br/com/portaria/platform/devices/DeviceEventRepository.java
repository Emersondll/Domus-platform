package br.com.portaria.platform.devices;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Acesso a eventos de dispositivos. */
public interface DeviceEventRepository extends JpaRepository<DeviceEvent, UUID> {

    long countByDeviceIdAndType(UUID deviceId, String type);
}
