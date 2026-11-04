package br.com.domus.platform.identity.repository;

import br.com.domus.platform.identity.entity.ProvisionedAccount;
import org.springframework.data.jpa.repository.JpaRepository;

/** Contas criadas pela API no provedor de identidade. */
public interface ProvisionedAccountRepository extends JpaRepository<ProvisionedAccount, String> {
}
