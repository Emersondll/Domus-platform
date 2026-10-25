package br.com.domus.platform.notifications.repository;

import br.com.domus.platform.notifications.entity.DirectMessage;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Acesso a mensagens individuais, sempre escopado por condomínio. */
public interface DirectMessageRepository extends JpaRepository<DirectMessage, UUID> {

    /** Resumo de uma conversa para a lista da portaria (sem o texto, que é cifrado). */
    interface ThreadRow {
        UUID getResidentUserId();

        Instant getLastAt();

        long getUnread();
    }

    Page<DirectMessage> findByCondominiumIdAndResidentUserId(UUID condominiumId, UUID residentUserId,
                                                             Pageable pageable);

    List<DirectMessage> findByCondominiumIdAndResidentUserIdAndFromResidentAndReadAtIsNull(
            UUID condominiumId, UUID residentUserId, boolean fromResident);

    long countByCondominiumIdAndResidentUserIdAndFromResidentAndCreatedAtAfter(
            UUID condominiumId, UUID residentUserId, boolean fromResident, Instant after);

    @Query("""
            SELECT m.residentUserId AS residentUserId, MAX(m.createdAt) AS lastAt,
                   SUM(CASE WHEN m.fromResident = true AND m.readAt IS NULL THEN 1L ELSE 0L END) AS unread
            FROM DirectMessage m WHERE m.condominiumId = :condominiumId
            GROUP BY m.residentUserId ORDER BY MAX(m.createdAt) DESC
            """)
    List<ThreadRow> threads(@Param("condominiumId") UUID condominiumId);
}
