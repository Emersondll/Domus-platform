package br.com.domus.platform.privacy.service;

import br.com.domus.platform.shared.crypto.FieldCipher;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Mantém os índices cegos (placa e e-mail) coerentes com {@code BLIND_INDEX_KEY}. O índice é um HMAC do texto decifrado, então
 * pode ser recalculado a qualquer momento sem perder dado: na partida, uma linha de amostra de cada tabela é conferida e, se o
 * hash guardado não bate com o da chave atual (chave trocada), <b>todos</b> são recalculados; linhas com hash nulo (dados
 * anteriores à coluna) também são preenchidas. Sem divergência, custa duas consultas. Não grava dado pessoal em log.
 *
 * <p>Trocar a chave exige reiniciar; enquanto instâncias com a chave antiga e a nova coexistirem, a unicidade pode falhar,
 * então a troca deve ser feita com a aplicação parada (ver {@code docs/operations.md}).
 */
@Component
public class BlindIndexReindexer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(BlindIndexReindexer.class);

    private final JdbcClient jdbc;
    private final FieldCipher cipher;
    private final boolean enabled;

    /** Construtor de BlindIndexReindexer; recebe as dependências por injeção. */
    public BlindIndexReindexer(JdbcClient jdbc, FieldCipher cipher,
                               @Value("${portaria.crypto.reindex-blind-on-start:true}") boolean enabled) {
        this.jdbc = jdbc;
        this.cipher = cipher;
        this.enabled = enabled;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!enabled) {
            return;
        }
        int rows = reindexIfNeeded();
        if (rows > 0) {
            log.warn("Blind indexes rebuilt for {} row(s) (BLIND_INDEX_KEY changed or columns were empty).", rows);
        }
    }

    /** @return quantidade de linhas recalculadas (0 se nada divergia) */
    @Transactional
    public int reindexIfNeeded() {
        int updated = 0;
        updated += reindex("vehicle", "plate", "plate_hash", "condominium_id", FieldCipher::normalizePlate, cipher::blindIndex);
        updated += reindex("app_user", "email", "email_hash", null, e -> e, cipher::emailIndex);
        return updated;
    }

    private int reindex(String table, String column, String hashColumn, String scopeColumn,
                        java.util.function.UnaryOperator<String> normalize, java.util.function.UnaryOperator<String> index) {
        // nomes de tabela/coluna são constantes deste arquivo (nunca de entrada externa)
        boolean stale = jdbc.sql("SELECT " + column + " AS value, " + hashColumn + " AS hash FROM " + table
                        + " WHERE " + hashColumn + " IS NOT NULL LIMIT 1")
                .query((rs, n) -> !index.apply(normalize.apply(cipher.decrypt(rs.getString("value"))))
                        .equals(rs.getString("hash"))).optional().orElse(false);
        String scope = scopeColumn == null ? "NULL" : "CAST(" + scopeColumn + " AS TEXT)";
        // hashes que continuam valendo (não recalculados): evitam violar a unicidade com dados legados duplicados
        java.util.Set<String> taken = new java.util.HashSet<>();
        if (!stale) {
            jdbc.sql("SELECT " + scope + " AS scope, " + hashColumn + " AS hash FROM " + table + " WHERE " + hashColumn
                    + " IS NOT NULL").query((rs, n) -> rs.getString("scope") + "|" + rs.getString("hash")).list()
                    .forEach(taken::add);
        }
        String filter = stale ? "" : " WHERE " + hashColumn + " IS NULL";
        List<Row> rows = jdbc.sql("SELECT id, " + scope + " AS scope, " + column + " AS value FROM " + table + filter)
                .query((rs, n) -> new Row(rs.getObject("id", UUID.class), rs.getString("scope"), rs.getString("value"))).list();
        int done = 0;
        int skipped = 0;
        for (Row r : rows) {
            if (r.value() == null) {
                continue;
            }
            String hash = index.apply(normalize.apply(cipher.decrypt(r.value())));
            if (!taken.add(r.scope() + "|" + hash)) {
                skipped++; // duplicado legado: fica sem índice em vez de impedir a partida
                continue;
            }
            jdbc.sql("UPDATE " + table + " SET " + hashColumn + " = :h WHERE id = :id")
                    .param("h", hash).param("id", r.id()).update();
            done++;
        }
        if (skipped > 0) {
            log.warn("Blind index of {}: {} duplicated row(s) left without index; review them.", table, skipped);
        }
        return done;
    }

    private record Row(UUID id, String scope, String value) {
    }
}
