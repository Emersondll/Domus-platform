package br.com.portaria.platform.devices.adapter;

/** Origem dos segredos de dispositivos: o banco guarda só o <i>nome</i> da variável; o valor vem daqui. */
@FunctionalInterface
public interface DeviceSecrets {

    /** Valor do segredo, ou {@code null} se não estiver definido. */
    String get(String name);
}
