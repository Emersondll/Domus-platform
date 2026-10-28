package br.com.domus.platform.devices.adapter;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/** Registro dos adaptadores disponíveis, por tipo. */
@Component
public class DeviceAdapters {

    private final Map<String, DeviceAdapter> byType;

    /** Construtor de DeviceAdapters; recebe as dependências por injeção. */
    public DeviceAdapters(List<DeviceAdapter> adapters) {
        this.byType = adapters.stream().collect(Collectors.toMap(DeviceAdapter::type, Function.identity()));
    }

    /**
     * Localiza o adaptador registrado para o tipo informado.
     *
     * @param type type
     * @return resultado, se existir
     */
    public Optional<DeviceAdapter> find(String type) {
        return Optional.ofNullable(byType.get(type));
    }
}
