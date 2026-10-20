package br.com.portaria.platform.devices.adapter;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Autenticação HTTP Digest (RFC 7616, algoritmo MD5, {@code qop=auth}) usada por controladoras Dahua/Intelbras.
 * O MD5 aqui é exigência do protocolo do equipamento, não uma escolha de segurança nossa: por isso o tráfego deve ir
 * por rede privada ou VPN e o usuário do equipamento deve ter só a permissão de abrir a porta.
 */
final class DigestAuth {

    private static final SecureRandom RANDOM = new SecureRandom();

    private DigestAuth() {
    }

    /** Monta o cabeçalho {@code Authorization} a partir do {@code WWW-Authenticate} recebido; vazio se não for Digest. */
    static Optional<String> authorize(String challenge, String method, String uri, String user, String password) {
        if (challenge == null || !challenge.regionMatches(true, 0, "Digest ", 0, 7)) {
            return Optional.empty();
        }
        Map<String, String> p = parse(challenge.substring(7));
        String realm = p.get("realm");
        String nonce = p.get("nonce");
        String algorithm = p.getOrDefault("algorithm", "MD5");
        if (realm == null || nonce == null || !"MD5".equalsIgnoreCase(algorithm)) {
            return Optional.empty();
        }
        boolean qopAuth = p.getOrDefault("qop", "").toLowerCase().contains("auth");
        String ha1 = md5(user + ":" + realm + ":" + password);
        String ha2 = md5(method + ":" + uri);
        StringBuilder h = new StringBuilder("Digest username=\"").append(escape(user))
                .append("\", realm=\"").append(escape(realm)).append("\", nonce=\"").append(escape(nonce))
                .append("\", uri=\"").append(uri).append("\", algorithm=MD5");
        String response;
        if (qopAuth) {
            byte[] raw = new byte[8];
            RANDOM.nextBytes(raw);
            String cnonce = HexFormat.of().formatHex(raw);
            response = md5(ha1 + ":" + nonce + ":00000001:" + cnonce + ":auth:" + ha2);
            h.append(", qop=auth, nc=00000001, cnonce=\"").append(cnonce).append('"');
        } else {
            response = md5(ha1 + ":" + nonce + ":" + ha2);
        }
        h.append(", response=\"").append(response).append('"');
        if (p.containsKey("opaque")) {
            h.append(", opaque=\"").append(escape(p.get("opaque"))).append('"');
        }
        return Optional.of(h.toString());
    }

    static Map<String, String> parse(String params) {
        Map<String, String> out = new LinkedHashMap<>();
        int i = 0;
        int n = params.length();
        while (i < n) {
            int eq = params.indexOf('=', i);
            if (eq < 0) {
                break;
            }
            String key = params.substring(i, eq).trim().replaceFirst("^,", "").trim().toLowerCase();
            i = eq + 1;
            String value;
            if (i < n && params.charAt(i) == '"') {
                int end = params.indexOf('"', i + 1);
                if (end < 0) {
                    break;
                }
                value = params.substring(i + 1, end);
                i = end + 1;
            } else {
                int end = params.indexOf(',', i);
                end = end < 0 ? n : end;
                value = params.substring(i, end).trim();
                i = end;
            }
            out.put(key, value);
            if (i < n && params.charAt(i) == ',') {
                i++;
            }
        }
        return out;
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String md5(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
