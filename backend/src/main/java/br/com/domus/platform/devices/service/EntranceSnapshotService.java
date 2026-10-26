package br.com.domus.platform.devices.service;

import br.com.domus.platform.devices.adapter.DeviceAdapter;
import br.com.domus.platform.devices.adapter.SnapshotImages;
import br.com.domus.platform.devices.adapter.SnapshotSource;
import br.com.domus.platform.devices.entity.Device;
import br.com.domus.platform.devices.repository.DeviceRepository;
import br.com.domus.platform.shared.EntranceCameraLookup;
import br.com.domus.platform.shared.error.ConflictException;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Captura o quadro da câmera de entrada de visitas e o entrega normalizado ao módulo de acesso. Não decide permissões. */
@Service
public class EntranceSnapshotService implements EntranceCameraLookup {

    private final DeviceRepository devices;
    private final List<SnapshotSource> sources;

    public EntranceSnapshotService(DeviceRepository devices, List<SnapshotSource> sources) {
        this.devices = devices;
        this.sources = sources;
    }

    @Override
    @Transactional(readOnly = true)
    public Capture captureVisitorEntrance(UUID condominiumId, String entrance) {
        List<Device> cameras = devices.findByCondominiumId(condominiumId).stream()
                .filter(d -> d.isVisitorEntrance() && d.isActive()).toList();
        Device camera;
        if (entrance == null || entrance.isBlank()) {
            if (cameras.size() != 1) {
                throw new ConflictException(cameras.isEmpty() ? "No visitor entrance camera is configured"
                        : "Choose the entrance for the image");
            }
            camera = cameras.get(0);
        } else {
            camera = cameras.stream().filter(d -> d.getEntrance().equals(entrance)).findFirst()
                    .orElseThrow(() -> new ConflictException("No visitor entrance camera for this entrance"));
        }
        SnapshotSource source = sources.stream().filter(s -> s.supports(camera)).findFirst()
                .orElseThrow(() -> new ConflictException("The camera has no image source (live video not available)"));
        try {
            return new Capture(camera.getId(), camera.getName(), SnapshotImages.normalize(source.capture(camera)));
        } catch (DeviceAdapter.DeviceCommunicationException e) {
            throw new ConflictException("The camera did not provide an image");
        }
    }

    @Override
    @Transactional(readOnly = true)
    public List<String> visitorEntranceNames(UUID condominiumId) {
        return devices.findByCondominiumId(condominiumId).stream().filter(d -> d.isVisitorEntrance() && d.isActive())
                .map(Device::getEntrance).sorted().toList();
    }
}
