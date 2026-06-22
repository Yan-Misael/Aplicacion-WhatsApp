package whatsapp.server.clock;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Reloj lógico de Lamport thread-safe para un nodo del sistema distribuido.
 *
 * <p>Reglas de Lamport:</p>
 * <ol>
 *   <li>Antes de enviar un mensaje: {@code tick()} para incrementar el reloj local.</li>
 *   <li>Adjuntar el valor resultante al mensaje enviado.</li>
 *   <li>Al recibir un mensaje con marca {@code L}: {@code update(L)} aplica
 *       {@code local = max(local, L) + 1}.</li>
 * </ol>
 */
public class LamportClock {

    private final AtomicLong clock = new AtomicLong(0);

    /**
     * Incrementa el reloj local antes de enviar un mensaje.
     *
     * @return el nuevo valor del reloj (a usar como {@code lamportTimestamp} del mensaje)
     */
    public long tick() {
        return clock.incrementAndGet();
    }

    /**
     * Actualiza el reloj al recibir un mensaje con marca {@code received}.
     * Aplica {@code local = max(local, received) + 1}.
     *
     * @param received marca lógica del mensaje recibido
     * @return el nuevo valor del reloj local después de la actualización
     */
    public long update(long received) {
        long updated, current;
        do {
            current = clock.get();
            updated = Math.max(current, received) + 1;
        } while (!clock.compareAndSet(current, updated));
        return updated;
    }

    /**
     * @return valor actual del reloj sin modificarlo
     */
    public long get() {
        return clock.get();
    }
}
