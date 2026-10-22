package br.com.domus.platform;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.domus.platform.devices.adapter.DeviceAdapter.Health;
import br.com.domus.platform.devices.adapter.HostPolicy;
import br.com.domus.platform.devices.adapter.OnvifAdapter;
import java.nio.charset.StandardCharsets;
import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;

/** Leitura segura da resposta ONVIF (sem Spring): XML válido e proteção contra XXE. */
class OnvifAdapterTest {

    private Object parse(String xml) throws Exception {
        Method m = OnvifAdapter.class.getDeclaredMethod("parse", byte[].class);
        m.setAccessible(true);
        return m.invoke(null, (Object) xml.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void validResponseIsOnline() throws Exception {
        Object report = parse("<e><Manufacturer>Acme</Manufacturer><Model>X</Model></e>");
        assertThat(report.toString()).contains("ONLINE");
    }

    @Test
    void doctypeIsRejectedToPreventXxe() throws Exception {
        Object report = parse("<?xml version=\"1.0\"?><!DOCTYPE e [<!ENTITY x SYSTEM \"file:///etc/passwd\">]>"
                + "<e><Model>&x;</Model></e>");
        assertThat(report.toString()).contains("UNKNOWN").doesNotContain("root:");
    }

    @Test
    void hostPolicyDeniesEverythingWhenListIsEmpty() {
        HostPolicy policy = new HostPolicy("");
        org.junit.jupiter.api.Assertions.assertThrows(Exception.class,
                () -> policy.requireAllowed("http://localhost:1", "/x"));
        assertThat(Health.valueOf("ONLINE")).isNotNull();
    }
}
