package br.com.domus.platform.privacy.service;

import br.com.domus.platform.shared.crypto.FieldCipher;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Cifra os dados pessoais que ainda estão em texto puro (instalação anterior à criptografia) e re-cifra com a chave ativa
 * os que usam uma chave antiga (rotação). Idempotente: valores já na chave ativa não são tocados. Também preenche o índice
 * cego da placa. Roda na partida só com {@code portaria.crypto.migrate-on-start=true} ({@code FIELD_ENCRYPTION_MIGRATE}),
 * para não varrer as tabelas a cada reinício. Não grava nenhum dado pessoal em log.
 */
@Component
public class PiiEncryptionMigration implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(PiiEncryptionMigration.class);

    /** tabela -> colunas pessoais (todas têm a coluna "id"). */
    static final Map<String, List<String>> COLUMNS = Map.of(
            "resident", List.of("full_name", "email", "phone"),
            "visitor", List.of("full_name"),
            "service_call", List.of("caller_name"),
            "app_user", List.of("email", "display_name"),
            "vehicle", List.of("plate"));

    private final JdbcClient jdbc;
    private final FieldCipher cipher;
    private final boolean migrateOnStart;

    /** Construtor de PiiEncryptionMigration; recebe as dependências por injeção. */
    public PiiEncryptionMigration(JdbcClient jdbc, FieldCipher cipher,
                                  @Value("${portaria.crypto.migrate-on-start:false}") boolean migrateOnStart) {
        this.jdbc = jdbc;
        this.cipher = cipher;
        this.migrateOnStart = migrateOnStart;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (migrateOnStart) {
            int rows = migrate();
            log.warn("PII encryption migration finished: {} row(s) updated. Turn FIELD_ENCRYPTION_MIGRATE off.", rows);
        }
    }

    /** @return quantidade de linhas atualizadas */
    @Transactional
    public int migrate() {
        int updated = 0;
        for (var entry : COLUMNS.entrySet()) {
            String table = entry.getKey();
            for (String column : entry.getValue()) {
                updated += migrateColumn(table, column);
            }
        }
        updated += backfillPlateHash();
        return updated;
    }

    private int migrateColumn(String table, String column) {
        // nomes de tabela/coluna vêm da constante acima (nunca de entrada externa): seguro concatenar
        List<Row> rows = jdbc.sql("SELECT id, " + column + " AS value FROM " + table + " WHERE " + column + " IS NOT NULL")
                .query((rs, n) -> new Row(rs.getObject("id", java.util.UUID.class), rs.getString("value"))).list();
        int count = 0;
        for (Row r : rows) {
            if (cipher.isCurrent(r.value())) {
                continue;
            }
            String plain = cipher.decrypt(r.value()); // legado (puro) passa direto; chave antiga é decifrada
            jdbc.sql("UPDATE " + table + " SET " + column + " = :v WHERE id = :id")
                    .param("v", cipher.encrypt(plain)).param("id", r.id()).update();
            count++;
        }
        return count;
    }

    private int backfillPlateHash() {
        List<Row> rows = jdbc.sql("SELECT id, plate AS value FROM vehicle WHERE plate_hash IS NULL")
                .query((rs, n) -> new Row(rs.getObject("id", java.util.UUID.class), rs.getString("value"))).list();
        for (Row r : rows) {
            String normalized = cipher.decrypt(r.value()).toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "");
            jdbc.sql("UPDATE vehicle SET plate_hash = :h WHERE id = :id")
                    .param("h", cipher.blindIndex(normalized)).param("id", r.id()).update();
        }
        return rows.size();
    }

    private record Row(java.util.UUID id, String value) {
    }
}
