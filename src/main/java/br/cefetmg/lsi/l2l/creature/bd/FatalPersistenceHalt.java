package br.cefetmg.lsi.l2l.creature.bd;

import br.cefetmg.lsi.l2l.metrics.MetricsExtension;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Shared "abort loudly" sequence for {@code PersistenceExtension.Impl}'s two fast-fail
 * detectors (queue-depth watchdog, BDActor death-watch) - see
 * docs/plans/arrow-ipc-write-path.md, W3. Per the plan's explicit no-feedback-loop decision,
 * this never tries to recover or degrade: log the marker, write a sentinel a human/ansible run
 * can grep for, give in-flight log/metric writes a couple seconds to land, then
 * {@link Runtime#halt}. {@code halt} (not {@code exit}) skips shutdown hooks deliberately - a
 * hung persistence layer is exactly the situation where running MORE code (finalizers, JVM
 * shutdown hooks that might themselves touch the stuck backend) is the wrong move; a nonzero
 * container exit code is what makes ansible fail the trial loudly instead of silently.
 */
final class FatalPersistenceHalt {

    private static final Logger logger = Logger.getLogger(FatalPersistenceHalt.class.getName());

    private FatalPersistenceHalt() {
    }

    static void trip(String marker, String detail, Path rawDumpDir, String sentinelName,
                      MetricsExtension.Impl metricsExt, int haltCode) {
        logger.severe(() -> marker + " " + detail);
        if (metricsExt != null) {
            try {
                metricsExt.setGauge("dl2l_persistence_fatal", 1);
            } catch (RuntimeException e) {
                logger.log(Level.WARNING, "Failed to set dl2l_persistence_fatal gauge before halt", e);
            }
        }
        if (rawDumpDir != null) {
            try {
                Files.writeString(rawDumpDir.resolve(sentinelName), marker + "\n" + detail + "\n");
            } catch (IOException e) {
                logger.log(Level.WARNING, "Failed to write " + sentinelName + " sentinel before halt", e);
            }
        }
        try {
            Thread.sleep(2000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        Runtime.getRuntime().halt(haltCode);
    }
}
