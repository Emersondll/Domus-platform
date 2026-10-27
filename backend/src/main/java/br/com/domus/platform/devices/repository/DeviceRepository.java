package br.com.domus.platform.devices.repository;

import br.com.domus.platform.devices.entity.Device;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Acesso a dispositivos, sempre escopado por condomínio (exceto a ingestão, que autentica por chave). */
public interface DeviceRepository extends JpaRepository<Device, UUID> {

    Page<Device> findByCondominiumId(UUID condominiumId, Pageable pageable);

    List<Device> findByCondominiumId(UUID condominiumId);

    Optional<Device> findByIdAndCondominiumId(UUID id, UUID condominiumId);

    /** Uso interno (assinatura de eventos): varre todos os tenants sem expor o resultado por API. */
    List<Device> findByAdapter(String adapter);

    boolean existsByCondominiumIdAndNameAndIdNot(UUID condominiumId, String name, UUID id);

    List<Device> findByCondominiumIdAndEntranceAndVisitorEntranceTrue(UUID condominiumId, String entrance);

    long countByCondominiumIdAndResidentViewableTrue(UUID condominiumId);

    List<Device> findByCondominiumIdAndResidentViewableTrue(UUID condominiumId);

    /**
     * Trava a linha do condomínio até o fim da transação: serializa quem libera câmeras ao morador, para o teto não ser
     * ultrapassado por duas liberações simultâneas.
     */
    @Query(value = "SELECT id FROM condominium WHERE id = :condominiumId FOR UPDATE", nativeQuery = true)
    List<UUID> lockCondominium(@Param("condominiumId") UUID condominiumId);

    boolean existsByCondominiumIdAndName(UUID condominiumId, String name);
}
