package br.com.domus.platform.devices.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** Equipamento físico (controladora, câmera, interfone) de um condomínio. */
@Entity
@Table(name = "device")
public class Device {

    @Id
    private UUID id;

    @Column(name = "condominium_id", nullable = false, updatable = false)
    private UUID condominiumId;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false, updatable = false)
    private String kind;

    @Column(nullable = false, updatable = false)
    private String adapter;

    @Column(nullable = false)
    private String entrance;

    @Convert(converter = ConfigConverter.class)
    @Column(nullable = false)
    private Map<String, String> config;

    @Column(name = "api_key_hash")
    private String apiKeyHash;

    @Column(nullable = false)
    private String status = "UNKNOWN";

    @Column(name = "resident_openable", nullable = false)
    private boolean residentOpenable;

    @Column(nullable = false)
    private boolean active = true;

    @Column(name = "visitor_entrance", nullable = false)
    private boolean visitorEntrance;

    @Column(name = "resident_viewable", nullable = false)
    private boolean residentViewable;

    @Column(name = "last_seen_at")
    private Instant lastSeenAt;

    protected Device() {
    }

    /** Construtor para criação de um(a) device. */
    public Device(UUID condominiumId, String name, String kind, String adapter, String entrance,
                  Map<String, String> config, String apiKeyHash) {
        this.id = UUID.randomUUID();
        this.condominiumId = condominiumId;
        this.name = name;
        this.kind = kind;
        this.adapter = adapter;
        this.entrance = entrance;
        this.config = config;
        this.apiKeyHash = apiKeyHash;
    }

    /**
     * Registra o último estado observado do dispositivo.
     *
     * @param status estado
     * @param when instante do fato
     */
    public void observe(String status, Instant when) {
        this.status = status;
        if ("ONLINE".equals(status)) {
            this.lastSeenAt = when;
        }
    }

    /** Edita o que não define a identidade do equipamento (tipo e adaptador não mudam). */
    public void update(String name, String entrance, Map<String, String> config) {
        this.name = name;
        this.entrance = entrance;
        this.config = config;
    }

    /** Define se o registro está ativo. */
    public void setActive(boolean active) {
        this.active = active;
        if (!active) {
            this.visitorEntrance = false; // câmera desativada não pode ser a câmera de entrada
            this.residentViewable = false;
        }
    }

    /** Define o campo visitor entrance. */
    public void setVisitorEntrance(boolean visitorEntrance) {
        this.visitorEntrance = visitorEntrance;
    }

    /** Define o campo resident viewable. */
    public void setResidentViewable(boolean residentViewable) {
        this.residentViewable = residentViewable;
    }

    /** Indica se resident viewable. */
    public boolean isResidentViewable() {
        return residentViewable;
    }

    /** Indica se active. */
    public boolean isActive() {
        return active;
    }

    /** Indica se visitor entrance. */
    public boolean isVisitorEntrance() {
        return visitorEntrance;
    }

    /**
     * Define se o morador pode abrir esta porta pelo app.
     *
     * @param allowed permitido ou não
     */
    public void allowResidentOpen(boolean allowed) {
        this.residentOpenable = allowed;
    }

    /** Indica se resident openable. */
    public boolean isResidentOpenable() {
        return residentOpenable;
    }

    /**
     * Troca o hash da chave do dispositivo.
     *
     * @param newHash novo hash da chave
     */
    public void rotateKey(String newHash) {
        this.apiKeyHash = newHash;
    }

    /** Retorna o identificador. */
    public UUID getId() {
        return id;
    }

    /** Retorna o condomínio dono do registro. */
    public UUID getCondominiumId() {
        return condominiumId;
    }

    /** Retorna o nome. */
    public String getName() {
        return name;
    }

    /** Retorna o tipo. */
    public String getKind() {
        return kind;
    }

    /** Retorna o campo adapter. */
    public String getAdapter() {
        return adapter;
    }

    /** Retorna o ponto de acesso. */
    public String getEntrance() {
        return entrance;
    }

    /** Retorna o campo config. */
    public Map<String, String> getConfig() {
        return config;
    }

    /** Retorna o campo api key hash. */
    public String getApiKeyHash() {
        return apiKeyHash;
    }

    /** Retorna o estado atual. */
    public String getStatus() {
        return status;
    }

    /** Retorna o campo last seen at. */
    public Instant getLastSeenAt() {
        return lastSeenAt;
    }
}
