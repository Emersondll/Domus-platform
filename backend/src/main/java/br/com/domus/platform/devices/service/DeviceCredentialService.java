package br.com.domus.platform.devices.service;

import br.com.domus.platform.devices.entity.Device;
import br.com.domus.platform.devices.repository.DeviceCredentialStore;
import br.com.domus.platform.shared.audit.AuditOutcome;
import br.com.domus.platform.shared.audit.AuditService;
import br.com.domus.platform.shared.crypto.FieldCipher;
import br.com.domus.platform.shared.error.ConflictException;
import br.com.domus.platform.shared.tenant.AccessLevel;
import br.com.domus.platform.shared.tenant.AccessService;
import br.com.domus.platform.shared.tenant.Actor;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Credenciais (usuário e senha) das câmeras cadastradas pela tela. São cifradas antes de ir ao banco e <b>nunca</b> saem pela
 * API: só se informa se estão configuradas e quando foram trocadas. Trocar e remover exigem nível MANAGE (síndico ou
 * administração) e ficam na auditoria sem o valor. O valor decifrado só é usado dentro do backend, para montar a fonte que o
 * servidor de mídia lê; não vai ao navegador, a log nem à auditoria.
 */
@Service
public class DeviceCredentialService {

    /**
     * Situação das credenciais de uma câmera.
     *
     * @param configured se há credencial cadastrada
     * @param updatedAt  última troca; nulo se não houver
     */
    public record Status(boolean configured, Instant updatedAt) {
    }

    private final DeviceService devices;
    private final DeviceCredentialStore store;
    private final FieldCipher cipher;
    private final AccessService access;
    private final AuditService audit;
    private final Clock clock;

    /** Construtor de DeviceCredentialService; recebe as dependências por injeção. */
    public DeviceCredentialService(DeviceService devices, DeviceCredentialStore store, FieldCipher cipher,
                                   AccessService access, AuditService audit, Clock clock) {
        this.devices = devices;
        this.store = store;
        this.cipher = cipher;
        this.access = access;
        this.audit = audit;
        this.clock = clock;
    }

    /**
     * Cadastra ou troca (rotação) a credencial da câmera.
     *
     * @param condominiumId condomínio
     * @param deviceId câmera
     * @param username usuário da câmera
     * @param password senha da câmera
     * @return situação após a troca
     */
    @Transactional
    public Status set(UUID condominiumId, UUID deviceId, String username, String password) {
        Actor actor = access.require(condominiumId, AccessLevel.MANAGE, "device.credentials.set");
        requireCamera(devices.find(condominiumId, deviceId));
        Instant now = clock.instant();
        store.upsert(deviceId, condominiumId, cipher.encrypt(username), cipher.encrypt(password), actor.userId(), now);
        audit.record(actor.userId(), null, condominiumId, "device.credentials.set", "device", deviceId.toString(),
                AuditOutcome.SUCCESS);
        return new Status(true, now);
    }

    /** Remove a credencial da câmera. */
    @Transactional
    public Status clear(UUID condominiumId, UUID deviceId) {
        Actor actor = access.require(condominiumId, AccessLevel.MANAGE, "device.credentials.clear");
        requireCamera(devices.find(condominiumId, deviceId));
        store.delete(deviceId);
        audit.record(actor.userId(), null, condominiumId, "device.credentials.clear", "device", deviceId.toString(),
                AuditOutcome.SUCCESS);
        return new Status(false, null);
    }

    /** Informa se a câmera tem credencial (nunca o valor). */
    @Transactional(readOnly = true)
    public Status status(UUID condominiumId, UUID deviceId) {
        access.require(condominiumId, AccessLevel.MANAGE, "device.credentials.status");
        requireCamera(devices.find(condominiumId, deviceId));
        return store.find(deviceId).map(s -> new Status(true, s.updatedAt())).orElse(new Status(false, null));
    }

    /**
     * Credencial pronta para a parte de autoridade de uma URL ({@code usuario:senha}, já com escape). Uso interno: quem chama
     * já decidiu que pode ler o fluxo da câmera; o resultado não pode ser registrado em log nem devolvido pela API.
     */
    @Transactional(readOnly = true)
    public Optional<String> userInfo(UUID deviceId) {
        return store.find(deviceId).map(s -> encode(cipher.decrypt(s.usernameEnc())) + ":" + encode(cipher.decrypt(s.passwordEnc())));
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static void requireCamera(Device device) {
        if (!"CAMERA".equals(device.getKind())) {
            throw new ConflictException("Credentials can only be stored for cameras");
        }
    }
}
