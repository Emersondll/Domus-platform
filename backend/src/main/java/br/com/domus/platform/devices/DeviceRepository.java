package br.com.domus.platform.devices;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/** Acesso a dispositivos, sempre escopado por condomínio (exceto a ingestão, que autentica por chave). */
public interface DeviceRepository extends JpaRepository<Device, UUID> {

    Page<Device> findByCondominiumId(UUID condominiumId, Pageable pageable);

    List<Device> findByCondominiumId(UUID condominiumId);

    Optional<Device> findByIdAndCondominiumId(UUID id, UUID condominiumId);

    /** Uso interno (assinatura de eventos): varre todos os tenants sem expor o resultado por API. */
    List<Device> findByAdapter(String adapter);

    boolean existsByCondominiumIdAndName(UUID condominiumId, String name);
}
