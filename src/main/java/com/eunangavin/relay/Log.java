package com.eunangavin.relay;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

/**
 * Minimal logging, deliberately not a library.
 *
 * <p>The server terminal is this service's observability window — registrations, queue
 * depths and deliveries all appear there. That wants short, aligned, readable lines, which
 * a logging framework's default format does not give without configuration and which is not
 * worth a dependency at this size.
 *
 * <p>Everything goes to stdout so a single terminal shows the whole picture. In a real
 * service this would be a proper logger with levels and structured output; that is a
 * documented limitation, not an oversight.
 */
public final class Log {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    private Log() {
    }

    public static void info(String format, Object... args) {
        write("INFO ", format, args);
    }

    public static void warn(String format, Object... args) {
        write("WARN ", format, args);
    }

    private static void write(String level, String format, Object... args) {
        // Single synchronized println: many virtual threads log concurrently, and
        // interleaved half-lines would make the output unreadable.
        String line = LocalTime.now().format(TIME) + "  " + level + "  " + format.formatted(args);
        synchronized (Log.class) {
            System.out.println(line);
        }
    }
}
