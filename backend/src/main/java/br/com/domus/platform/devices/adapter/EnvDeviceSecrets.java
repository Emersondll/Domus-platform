package br.com.domus.platform.devices.adapter;

import org.springframework.stereotype.Component;

/** Lê os segredos das variáveis de ambiente do processo. */
@Component
class EnvDeviceSecrets implements DeviceSecrets {

    @Override
    public String get(String name) {
        return System.getenv(name);
    }
}
