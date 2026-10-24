package br.com.domus.platform.devices.repository;

import br.com.domus.platform.devices.entity.RemoteOpenPolicy;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Política de abertura remota; a chave é o próprio condomínio. */
public interface RemoteOpenPolicyRepository extends JpaRepository<RemoteOpenPolicy, UUID> {
}
