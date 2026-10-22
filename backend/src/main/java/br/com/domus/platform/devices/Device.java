package br.com.domus.platform.devices;

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
