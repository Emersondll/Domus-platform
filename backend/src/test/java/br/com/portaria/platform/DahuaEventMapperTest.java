package br.com.portaria.platform;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.portaria.platform.devices.adapter.DahuaEventMapper;
import br.com.portaria.platform.devices.adapter.DahuaEventStream.Event;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Tradução de eventos: só o identificador do nosso comando permite confirmar. */
class DahuaEventMapperTest {

    private final UUID id = UUID.randomUUID();

    @Test
    void accessControlEventEchoingOurCommandIdCarriesTheCorrelation() {
        var m = DahuaEventMapper.map(new Event("AccessControl", "Pulse", "0",
                "{\"Status\":1,\"UserID\":\"portaria-" + id + "\"}")).orElseThrow();
        assertThat(m.type()).isEqualTo("DOOR_OPENED");
        assertThat(m.commandId()).isEqualTo(id);
    }

    @Test
    void eventWithoutOurIdentifierNeverCarriesACommand() {
        var m = DahuaEventMapper.map(new Event("AccessControl", "Pulse", "0", "{\"Status\":1,\"UserID\":\"1001\"}")).orElseThrow();
        assertThat(m.type()).isEqualTo("DOOR_OPENED");
        assertThat(m.commandId()).isNull();
    }

    @Test
    void deniedAccessIsNotAnOpening() {
        var m = DahuaEventMapper.map(new Event("AccessControl", "Pulse", "0",
                "{\"Status\":0,\"UserID\":\"portaria-" + id + "\"}")).orElseThrow();
        assertThat(m.type()).isEqualTo("ACCESS_DENIED");
    }

    @Test
    void doorStatusIsInformationalAndNeverCorrelated() {
        assertThat(DahuaEventMapper.map(new Event("DoorStatus", "Pulse", "0", "{\"Status\":\"Open\"}")).orElseThrow().commandId()).isNull();
        assertThat(DahuaEventMapper.map(new Event("DoorStatus", "Pulse", "0", "{\"Status\":\"Close\"}")).orElseThrow().type())
                .isEqualTo("DOOR_CLOSED");
    }

    @Test
    void unknownCodesMalformedDataAndStopAreIgnoredOrHarmless() {
        assertThat(DahuaEventMapper.map(new Event("VideoMotion", "Start", "0", null))).isEmpty();
        assertThat(DahuaEventMapper.map(new Event("AccessControl", "Stop", "0", "{}"))).isEmpty();
        assertThat(DahuaEventMapper.map(new Event("DoorStatus", "Pulse", "0", "{not json"))).isEmpty();
        // dado ilegível não derruba o tradutor nem forja correlação
        assertThat(DahuaEventMapper.map(new Event("AccessControl", "Pulse", "0", "{broken")).orElseThrow().commandId()).isNull();
    }
}
