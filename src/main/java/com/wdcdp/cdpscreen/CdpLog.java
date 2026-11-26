package com.wdcdp.cdpscreen;

import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Logging facade.
 *
 * <p>Deliberately {@code java.util.logging} rather than SLF4J: this module has to
 * run in three places with different logging stacks - inside Minecraft (where
 * NeoForge installs a JUL bridge), in the standalone CDP probe, and in the pure
 * JVM reflection validator. JUL is present in all three and needs no shading.</p>
 */
public final class CdpLog {

    private static final Logger LOG = Logger.getLogger("wdcdp");

    private CdpLog() {
    }

    public static void info(String message) {
        LOG.info(message);
    }

    public static void warn(String message) {
        LOG.warning(message);
    }

    public static void warn(String message, Throwable cause) {
        LOG.log(Level.WARNING, message, cause);
    }

    public static void error(String message, Throwable cause) {
        LOG.log(Level.SEVERE, message, cause);
    }

    public static void debug(String message) {
        LOG.fine(message);
    }
}
