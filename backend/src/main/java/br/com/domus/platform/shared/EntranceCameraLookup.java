package br.com.domus.platform.shared;

import java.util.List;
import java.util.UUID;

/** Contrato para o módulo de acesso obter a imagem da câmera de entrada sem depender do módulo de dispositivos. */
public interface EntranceCameraLookup {

    /** Quadro capturado, já normalizado (JPEG de no máximo 640 px). */
    record Capture(UUID deviceId, String cameraName, byte[] jpeg) {
    }

    /**
     * Captura um quadro da câmera de entrada de visitas.
     *
     * @param entrance entrada desejada; nulo quando o condomínio tem uma só câmera de entrada
     * @throws br.com.domus.platform.shared.error.ConflictException se não há câmera, ela não responde ou não tem fonte de imagem
     */
    Capture captureVisitorEntrance(UUID condominiumId, String entrance);

    /** Entradas que têm câmera de entrada de visitas ativa. */
    List<String> visitorEntranceNames(UUID condominiumId);
}
