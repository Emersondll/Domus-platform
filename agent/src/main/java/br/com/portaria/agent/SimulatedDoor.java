package br.com.portaria.agent;

import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Controladora simulada para desenvolvimento e testes; a disponibilidade pode ser alternada. */
public final class SimulatedDoor implements DoorController {

    private static final Logger log = LoggerFactory.getLogger(SimulatedDoor.class);

    private volatile boolean available = true;
    private final AtomicInteger opens = new AtomicInteger();

    public void setAvailable(boolean available) {
        this.available = available;
    }

    public int opens() {
        return opens.get();
    }

    @Override
    public void open(String entrance) throws DoorException {
        if (!available) {
            throw new DoorException("controller unavailable");
        }
        opens.incrementAndGet();
        log.info("[simulated door] opened entrance={}", entrance);
    }
}
