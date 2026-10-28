package br.com.domus.platform.residents.service;

import br.com.domus.platform.residents.entity.ResidentType;
import br.com.domus.platform.residents.repository.ResidentRepository;
import br.com.domus.platform.shared.ResidentProfileLookup;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Responde, para os outros módulos, se um morador é adulto (dependentes ficam fora do vídeo e das notificações de câmera). */
@Service
class ResidentProfileLookupAdapter implements ResidentProfileLookup {

    private final ResidentRepository residents;

    ResidentProfileLookupAdapter(ResidentRepository residents) {
        this.residents = residents;
    }

    /** {@inheritDoc} */
    @Override
    @Transactional(readOnly = true)
    public boolean isAdult(UUID condominiumId, UUID residentId) {
        return residents.findByIdAndCondominiumId(residentId, condominiumId)
                .map(r -> r.getResidentType() != ResidentType.DEPENDENT).orElse(false);
    }
}
