package br.com.domus.platform.devices.adapter;

import java.net.URI;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Proteção contra SSRF: a central só fala com hosts explicitamente permitidos
 * ({@code portaria.devices.http-allowed-hosts}). Lista vazia = nenhum host.
 */
@Component
public class HostPolicy {

    private final Set<String> allowed;

    /** Construtor de HostPolicy; recebe as dependências por injeção. */
    public HostPolicy(@Value("${portaria.devices.http-allowed-hosts:}") String hosts) {
        this.allowed = Arrays.stream(hosts.split(","))
                .map(String::trim).filter(s -> !s.isEmpty()).collect(Collectors.toSet());
    }

    /**
     * Exige que o host esteja na lista permitida (proteção contra SSRF).
     *
     * @param baseUrl base url
     * @param path path
     */
    public URI requireAllowed(String baseUrl, String path) throws DeviceAdapter.DeviceCommunicationException {
        URI uri;
        try {
            uri = URI.create(baseUrl + path);
        } catch (IllegalArgumentException e) {
            throw new DeviceAdapter.DeviceCommunicationException("Invalid device URL");
        }
        String scheme = uri.getScheme();
        if (!("http".equals(scheme) || "https".equals(scheme)) || uri.getHost() == null
                || !allowed.contains(uri.getHost())) {
            throw new DeviceAdapter.DeviceCommunicationException("Device host is not allowed by policy");
        }
        return uri;
    }

    /** Valida o endereço RTSP de uma câmera: esquema rtsp/rtsps, sem credenciais embutidas e host permitido. */
    public URI requireAllowedRtsp(String url) {
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid RTSP URL");
        }
        if (!("rtsp".equals(uri.getScheme()) || "rtsps".equals(uri.getScheme())) || uri.getHost() == null
                || uri.getUserInfo() != null || !allowed.contains(uri.getHost())) {
            throw new IllegalArgumentException("RTSP URL must be rtsp(s), without credentials, on an allowed host");
        }
        return uri;
    }
}
