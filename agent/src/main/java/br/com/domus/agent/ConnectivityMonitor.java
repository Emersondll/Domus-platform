package br.com.domus.agent;

import java.time.Clock;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Estado de conectividade com a central. Começa OFFLINE até o primeiro contato bem-sucedido. */
public final class ConnectivityMonitor {

    private static final Logger log = LoggerFactory.getLogger(ConnectivityMonitor.class);

    private final Clock clock;
    private volatile boolean online;
    private volatile Instant lastSuccess;
    private volatile Instant offlineSince;

    public ConnectivityMonitor(Clock clock) {
        this.clock = clock;
        this.offlineSince = Instant.now(clock);
    }

    public void success() {
        if (!online) {
            log.info("Connectivity restored");
        }
        online = true;
        lastSuccess = Instant.now(clock);
        offlineSince = null;
    }

    public void failure(String reason) {
        if (online || offlineSince == null) {
            log.warn("Connectivity lost: {}", reason);
            offlineSince = Instant.now(clock);
        }
        online = false;
    }

    public boolean isOnline() {
        return online;
    }

    public Instant lastSuccess() {
        return lastSuccess;
    }
}
