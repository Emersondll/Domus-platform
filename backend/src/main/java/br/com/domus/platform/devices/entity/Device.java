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

    @Column(name = "last_seen_at")
    private Instant lastSeenAt;

    protected Device() {
    }

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

    public void setActive(boolean active) {
        this.active = active;
        if (!active) {
            this.visitorEntrance = false; // câmera desativada não pode ser a câmera de entrada
        }
    }

    public void setVisitorEntrance(boolean visitorEntrance) {
        this.visitorEntrance = visitorEntrance;
    }

    public boolean isActive() {
        return active;
    }

    public boolean isVisitorEntrance() {
        return visitorEntrance;
    }

    public void allowResidentOpen(boolean allowed) {
        this.residentOpenable = allowed;
    }

    public boolean isResidentOpenable() {
        return residentOpenable;
    }

    public void rotateKey(String newHash) {
        this.apiKeyHash = newHash;
    }

    public UUID getId() {
        return id;
    }

    public UUID getCondominiumId() {
        return condominiumId;
    }

    public String getName() {
        return name;
    }

    public String getKind() {
        return kind;
    }

    public String getAdapter() {
        return adapter;
    }

    public String getEntrance() {
        return entrance;
    }

    public Map<String, String> getConfig() {
        return config;
    }

    public String getApiKeyHash() {
        return apiKeyHash;
    }

    public String getStatus() {
        return status;
    }

    public Instant getLastSeenAt() {
        return lastSeenAt;
    }
}
