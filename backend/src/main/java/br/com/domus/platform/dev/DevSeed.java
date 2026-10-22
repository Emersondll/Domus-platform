package br.com.domus.platform.dev;

import br.com.domus.platform.commercial.Block;
import br.com.domus.platform.commercial.BlockRepository;
import br.com.domus.platform.commercial.Condominium;
import br.com.domus.platform.commercial.CondominiumRepository;
import br.com.domus.platform.commercial.OperatorCompany;
import br.com.domus.platform.commercial.OperatorCompanyRepository;
import br.com.domus.platform.commercial.Unit;
import br.com.domus.platform.commercial.UnitRepository;
import br.com.domus.platform.devices.Device;
import br.com.domus.platform.devices.DeviceRepository;
import br.com.domus.platform.identity.AppUser;
import br.com.domus.platform.identity.AppUserRepository;
import br.com.domus.platform.identity.Membership;
import br.com.domus.platform.identity.MembershipRepository;
import br.com.domus.platform.identity.Role;
import br.com.domus.platform.residents.Resident;
import br.com.domus.platform.residents.ResidentRepository;
import br.com.domus.platform.residents.ResidentType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Dados fictícios para validação local (nenhum dado real de morador). */
@Component
@Profile("dev")
class DevSeed implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DevSeed.class);

    private final OperatorCompanyRepository companies;
    private final CondominiumRepository condominiums;
    private final BlockRepository blocks;
    private final UnitRepository units;
    private final ResidentRepository residents;
    private final AppUserRepository users;
    private final MembershipRepository memberships;
    private final DeviceRepository devices;

    DevSeed(OperatorCompanyRepository companies, CondominiumRepository condominiums, BlockRepository blocks,
            UnitRepository units, ResidentRepository residents, AppUserRepository users,
            MembershipRepository memberships, DeviceRepository devices) {
        this.companies = companies;
        this.condominiums = condominiums;
        this.blocks = blocks;
        this.units = units;
        this.residents = residents;
        this.users = users;
        this.memberships = memberships;
        this.devices = devices;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (companies.count() > 0) {
            return;
        }
        OperatorCompany alpha = companies.save(new OperatorCompany("Central Alfa Monitoramento", "11111111000111"));
        OperatorCompany beta = companies.save(new OperatorCompany("Central Beta Portaria", "22222222000122"));

        Condominium sol = condominiums.save(new Condominium(alpha.getId(), "Residencial Sol", null));
        Condominium lua = condominiums.save(new Condominium(alpha.getId(), "Residencial Lua", null));
        Condominium mar = condominiums.save(new Condominium(beta.getId(), "Residencial Mar", null));

        for (Condominium c : new Condominium[] {sol, lua, mar}) {
            Block a = blocks.save(new Block(c.getId(), "A"));
            Unit u101 = units.save(new Unit(c.getId(), a.getId(), "101"));
            units.save(new Unit(c.getId(), a.getId(), "102"));
            Resident resident = residents.save(new Resident(c.getId(), u101.getId(),
                    "Morador Exemplo " + c.getName(), "morador@example.invalid", "+55 11 90000-0000",
                    ResidentType.OWNER));
            if (c == sol) {
                AppUser u = users.save(new AppUser("resident-sol", "resident-sol@example.invalid", "Morador Sol"));
                memberships.save(Membership.forResident(u.getId(), sol.getId(), resident.getId()));
            }
        }

        // Dispositivos simulados (sem hardware): um confirma a abertura, outro aceita mas nunca confirma.
        devices.save(new Device(sol.getId(), "Portão principal", "ACCESS_CONTROLLER", "SIMULATOR", "portaria",
                java.util.Map.of("behavior", "CONFIRM"), null));
        devices.save(new Device(sol.getId(), "Portão da garagem (simulado sem confirmação)", "ACCESS_CONTROLLER",
                "SIMULATOR", "garagem", java.util.Map.of("behavior", "SILENT"), null));

        user("platform-admin", "Admin da Plataforma", Role.PLATFORM_ADMIN, null, null);
        user("alpha-admin", "Admin Alfa", Role.COMPANY_ADMIN, alpha.getId(), null);
        user("beta-admin", "Admin Beta", Role.COMPANY_ADMIN, beta.getId(), null);
        user("operator-sol", "Operador Sol", Role.OPERATOR, null, sol.getId());
        AppUser multi = user("operator-multi", "Operador Multi", Role.OPERATOR, null, sol.getId());
        memberships.save(new Membership(multi.getId(), Role.OPERATOR, null, lua.getId()));
        user("manager-sol", "Síndico Sol", Role.CONDO_MANAGER, null, sol.getId());
        log.info("Dev seed loaded. Tokens: GET /dev/token?subject=<platform-admin|alpha-admin|beta-admin|"
                + "operator-sol|operator-multi|manager-sol|resident-sol>");
    }

    private AppUser user(String subject, String name, Role role,
                         java.util.UUID companyId, java.util.UUID condominiumId) {
        AppUser user = users.save(new AppUser(subject, subject + "@example.invalid", name));
        memberships.save(new Membership(user.getId(), role, companyId, condominiumId));
        return user;
    }
}
