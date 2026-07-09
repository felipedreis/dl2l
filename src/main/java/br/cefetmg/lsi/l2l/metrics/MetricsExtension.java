package br.cefetmg.lsi.l2l.metrics;

import akka.NotUsed;
import akka.actor.AbstractExtensionId;
import akka.actor.ActorSystem;
import akka.actor.ExtendedActorSystem;
import akka.actor.Extension;
import akka.http.javadsl.ConnectHttp;
import akka.http.javadsl.Http;
import akka.http.javadsl.server.AllDirectives;
import akka.http.javadsl.server.Route;
import akka.stream.Materializer;
import io.micrometer.core.instrument.Tags;
import io.micrometer.prometheus.PrometheusConfig;
import io.micrometer.prometheus.PrometheusMeterRegistry;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;

/**
 * Akka Extension that creates one Micrometer/Prometheus registry per JVM node
 * and exposes it over HTTP for scraping. Creature components read/write metrics
 * exclusively through MetricsExtension.of(system) — never by holding a direct
 * reference to another actor's state.
 *
 * Usage: MetricsExtension.of(context().system()).setGauge("dl2l_creature_arousal", creatureId, value)
 */
public class MetricsExtension extends AbstractExtensionId<MetricsExtension.Impl> {

    public static final MetricsExtension Id = new MetricsExtension();

    public static Impl of(ActorSystem system) {
        return system.registerExtension(Id);
    }

    @Override
    public Impl createExtension(ExtendedActorSystem system) {
        return new Impl(system);
    }

    // -------------------------------------------------------------------------

    public static class Impl extends AllDirectives implements Extension {

        private static final Logger logger = Logger.getLogger(Impl.class.getName());
        private static final String HOST = "0.0.0.0";
        private static final int PORT = 9091;

        private final PrometheusMeterRegistry registry;

        // Keeps a strong reference to each gauge's backing value so Micrometer's
        // internal weak reference to it never gets collected between updates.
        private final ConcurrentHashMap<String, AtomicReference<Double>> gaugeHolders =
                new ConcurrentHashMap<>();

        Impl(ExtendedActorSystem system) {
            registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);

            String simulation = system.settings().config().hasPath("dl2l.metrics.simulation-name")
                    ? system.settings().config().getString("dl2l.metrics.simulation-name")
                    : "unknown";
            String trial = system.settings().config().hasPath("dl2l.metrics.trial-id")
                    ? system.settings().config().getString("dl2l.metrics.trial-id")
                    : "local";
            registry.config().commonTags("simulation", simulation, "trial", trial);

            bindHttpServer(system);

            system.registerOnTermination(() -> {
                // In-memory registry; nothing external to flush or close.
            });
        }

        public PrometheusMeterRegistry registry() {
            return registry;
        }

        /**
         * Get-or-create a per-creature gauge and set its current value.
         * Safe to call every tick from any actor/thread — Micrometer registries
         * and AtomicReference are both thread-safe.
         */
        public void setGauge(String name, String creatureId, double value) {
            gaugeHolders.computeIfAbsent(name + "|" + creatureId, k ->
                    registry.gauge(name, Tags.of("creature", creatureId),
                            new AtomicReference<>(value), AtomicReference::get)
            ).set(value);
        }

        private void bindHttpServer(ExtendedActorSystem system) {
            Materializer materializer = Materializer.matFromSystem(system);
            Route route = path("metrics", () -> complete(registry.scrape()));

            Http.get(system)
                    .bindAndHandle(route.flow(system, materializer),
                            ConnectHttp.toHost(HOST, PORT), materializer)
                    .<NotUsed>thenApply(binding -> {
                        system.log().info("MetricsExtension: /metrics exposed at http://{}:{}/metrics", HOST, PORT);
                        return null;
                    })
                    .exceptionally(ex -> {
                        logger.severe("MetricsExtension: failed to bind metrics HTTP server: " + ex.getMessage());
                        return null;
                    });
        }
    }
}
