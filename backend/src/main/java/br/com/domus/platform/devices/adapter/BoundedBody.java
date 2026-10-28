package br.com.domus.platform.devices.adapter;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpResponse;

/**
 * Leitura de resposta de equipamento com teto de tamanho. Um equipamento (ou quem se passa por ele) que devolve um corpo
 * enorme não pode esgotar a memória da central. Use com {@code BodyHandlers.ofInputStream()}.
 */
final class BoundedBody {

    /** Teto padrão: respostas de status/ONVIF/CGI são de poucos KB. */
    static final int DEFAULT_MAX_BYTES = 256 * 1024;

    private BoundedBody() {
    }

    /**
     * Lê a resposta até {@code maxBytes} e fecha o fluxo.
     *
     * @param response resposta com corpo em fluxo
     * @param maxBytes teto em bytes
     * @return o corpo
     * @throws IOException se o corpo ultrapassar o teto ou a leitura falhar
     */
    static byte[] read(HttpResponse<InputStream> response, int maxBytes) throws IOException {
        try (InputStream in = response.body()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int n;
            while ((n = in.read(buffer)) != -1) {
                if (out.size() + n > maxBytes) {
                    throw new IOException("Device response exceeds " + maxBytes + " bytes");
                }
                out.write(buffer, 0, n);
            }
            return out.toByteArray();
        }
    }
}
