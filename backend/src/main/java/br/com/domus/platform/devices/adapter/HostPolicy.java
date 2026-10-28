package br.com.domus.platform.devices.adapter;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Proteção contra SSRF em duas camadas. <b>1) Lista explícita:</b> a central só fala com hosts permitidos
 * ({@code portaria.devices.http-allowed-hosts}); lista vazia = nenhum host. Endereços privados (192.168.x, 10.x) e o
 * loopback <b>não</b> são bloqueados em si, porque as câmeras ficam em redes privadas; o que decide é a lista.
 * <b>2) Endereço resolvido:</b> mesmo um host da lista é recusado se o nome resolver para um destino que nenhuma câmera
 * legítima usa: link-local (inclui o serviço de metadados da nuvem, 169.254.169.254, e o IPv6 {@code fe80::/10}),
 * endereço não especificado ({@code 0.0.0.0}, {@code ::}), multicast e os endereços de metadados conhecidos. A resolução é
 * refeita a <b>cada</b> pedido (nada fica em cache aqui), o que reduz a janela de DNS rebinding; ela não a elimina, já que o
 * cliente HTTP resolve de novo ao conectar. Por isso o egress do contêiner da API também deve ser restrito na rede
 * (ver {@code docs/security.md}). Redirecionamentos não são seguidos por nenhum adaptador.
 */
@Component
public class HostPolicy {

    /** Resolve um nome em endereços. Separado para os testes não dependerem de DNS. */
    @FunctionalInterface
    public interface Resolver {
        /** @return todos os endereços do nome; {@link UnknownHostException} se não resolver */
        InetAddress[] resolve(String host) throws UnknownHostException;
    }

    /** Endereços de metadados de nuvem fora da faixa link-local (Alibaba e o IPv6 da AWS). */
    private static final Set<String> METADATA = Set.of("100.100.100.200", "fd00:ec2:0:0:0:0:0:254");

    private final Set<String> allowed;
    private final Resolver resolver;

    /** Construtor de HostPolicy; recebe as dependências por injeção. */
    @Autowired
    public HostPolicy(@Value("${portaria.devices.http-allowed-hosts:}") String hosts) {
        this(hosts, InetAddress::getAllByName);
    }

    /** Construtor com resolvedor próprio (testes). */
    public HostPolicy(String hosts, Resolver resolver) {
        this.allowed = Arrays.stream(hosts.split(","))
                .map(String::trim).filter(s -> !s.isEmpty()).collect(Collectors.toSet());
        this.resolver = resolver;
    }

    /**
     * Valida uma URL HTTP(S) de equipamento.
     *
     * @param baseUrl endereço base cadastrado
     * @param path caminho a acrescentar
     * @return a URI validada
     * @throws DeviceAdapter.DeviceCommunicationException se o host não for permitido ou resolver para um destino proibido
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
                || uri.getUserInfo() != null || !allowed.contains(uri.getHost())) {
            throw new DeviceAdapter.DeviceCommunicationException("Device host is not allowed by policy");
        }
        if (!resolvesToPermittedAddresses(uri.getHost())) {
            throw new DeviceAdapter.DeviceCommunicationException("Device host resolves to a forbidden address");
        }
        return uri;
    }

    /**
     * Valida uma URL RTSP de câmera: esquema rtsp(s), sem credenciais embutidas, host da lista e endereço permitido.
     *
     * @param url endereço RTSP
     * @return a URI validada
     * @throws IllegalArgumentException se for recusada
     */
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
        if (!resolvesToPermittedAddresses(uri.getHost())) {
            throw new IllegalArgumentException("RTSP host resolves to a forbidden address");
        }
        return uri;
    }

    /** Todos os endereços do host precisam ser permitidos; nome que não resolve é recusado. */
    private boolean resolvesToPermittedAddresses(String host) {
        String name = host.startsWith("[") && host.endsWith("]") ? host.substring(1, host.length() - 1) : host;
        try {
            InetAddress[] addresses = resolver.resolve(name);
            return addresses.length > 0 && Arrays.stream(addresses).noneMatch(HostPolicy::forbidden);
        } catch (UnknownHostException e) {
            return false;
        }
    }

    static boolean forbidden(InetAddress a) {
        if (a.isAnyLocalAddress() || a.isLinkLocalAddress() || a.isMulticastAddress()) {
            return true;
        }
        // ::ffff:a.b.c.d (IPv4 mapeado) já chega como Inet4Address; o JDK imprime IPv6 sem zeros à esquerda
        String text = a.getHostAddress().toLowerCase(Locale.ROOT);
        return METADATA.contains(text);
    }
}
