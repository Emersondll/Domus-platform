package br.com.domus.platform.devices.adapter;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Set;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;

/**
 * Câmeras/dispositivos ONVIF: verifica disponibilidade com a operação padrão {@code GetDeviceInformation}
 * (Device Management). Não abre portas (ONVIF Profile S/T não é controle de acesso). Implementado conforme a
 * especificação e validado apenas contra um servidor local de teste; NÃO validado com câmera real.
 * O XML é lido com DTDs desabilitados (proteção contra XXE).
 */
@Component
public class OnvifAdapter implements DeviceAdapter {

    private static final String ENVELOPE = """
            <?xml version="1.0" encoding="UTF-8"?>
            <s:Envelope xmlns:s="http://www.w3.org/2003/05/soap-envelope">
              <s:Body><GetDeviceInformation xmlns="http://www.onvif.org/ver10/device/wsdl"/></s:Body>
            </s:Envelope>""";

    private final HostPolicy hosts;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

    public OnvifAdapter(HostPolicy hosts) {
        this.hosts = hosts;
    }

    @Override
    public String type() {
        return "ONVIF";
    }

    @Override
    public Set<Capability> capabilities() {
        return Set.of(Capability.STATUS, Capability.VIDEO_STREAM);
    }

    @Override
    public StatusReport getStatus(DeviceDescriptor device) {
        try {
            URI uri = hosts.requireAllowed(device.config().getOrDefault("baseUrl", ""), "/onvif/device_service");
            HttpResponse<byte[]> response = client.send(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(3))
                    .header("Content-Type", "application/soap+xml; charset=utf-8")
                    .POST(HttpRequest.BodyPublishers.ofString(ENVELOPE, StandardCharsets.UTF_8)).build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() / 100 != 2) {
                return new StatusReport(Health.OFFLINE, "HTTP " + response.statusCode());
            }
            return parse(response.body());
        } catch (DeviceCommunicationException | IOException e) {
            return new StatusReport(Health.OFFLINE, e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new StatusReport(Health.UNKNOWN, "interrupted");
        }
    }

    static StatusReport parse(byte[] xml) {
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            f.setNamespaceAware(true);
            Document doc = f.newDocumentBuilder().parse(new ByteArrayInputStream(xml));
            String model = text(doc, "Model");
            String manufacturer = text(doc, "Manufacturer");
            return model == null && manufacturer == null
                    ? new StatusReport(Health.UNKNOWN, "Unexpected ONVIF response")
                    : new StatusReport(Health.ONLINE, (manufacturer == null ? "" : manufacturer + " ")
                            + (model == null ? "" : model));
        } catch (Exception e) {
            return new StatusReport(Health.UNKNOWN, "Unparseable ONVIF response");
        }
    }

    private static String text(Document doc, String localName) {
        var nodes = doc.getElementsByTagNameNS("*", localName);
        return nodes.getLength() == 0 ? null : nodes.item(0).getTextContent().trim();
    }

    @Override
    public CommandReceipt requestAccess(DeviceDescriptor device, AccessRequest request)
            throws DeviceCommunicationException {
        throw new DeviceCommunicationException("ONVIF devices do not support door control");
    }
}
