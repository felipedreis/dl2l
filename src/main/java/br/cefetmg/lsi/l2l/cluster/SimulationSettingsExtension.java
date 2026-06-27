package br.cefetmg.lsi.l2l.cluster;

import akka.actor.AbstractExtensionId;
import akka.actor.ActorSystem;
import akka.actor.Extension;
import akka.actor.ExtendedActorSystem;
import br.cefetmg.lsi.l2l.cluster.settings.LearningSettings;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Akka Extension that makes the simulation's LearningSettings available to any actor
 * on this JVM node without passing them through every constructor.
 *
 * Global settings are initialised once from Holder.preStart():
 *   SimulationSettingsExtension.of(system).configure(simulation.getLearningSettings())
 *
 * Per-creature overrides are registered by CreatureActor.init() and released in kill():
 *   SimulationSettingsExtension.of(system).configure(id.key, perCreatureSettings)
 *   SimulationSettingsExtension.of(system).releaseCreatureSettings(id.key)
 *
 * Components read the effective settings (per-creature if set, else global) in preStart():
 *   LearningSettings s = SimulationSettingsExtension.of(context().system()).learningSettings(id.key);
 */
public class SimulationSettingsExtension extends AbstractExtensionId<SimulationSettingsExtension.Impl> {

    public static final SimulationSettingsExtension Id = new SimulationSettingsExtension();

    public static Impl of(ActorSystem system) {
        return system.registerExtension(Id);
    }

    @Override
    public Impl createExtension(ExtendedActorSystem system) {
        return new Impl();
    }

    public static class Impl implements Extension {

        private volatile LearningSettings globalSettings = new LearningSettings();

        private final ConcurrentHashMap<Long, LearningSettings> perCreatureSettings = new ConcurrentHashMap<>();

        /** Called once by Holder before any creature actor is created. */
        public void configure(LearningSettings settings) {
            this.globalSettings = settings;
        }

        /** Register a per-creature override; called by CreatureActor.init() before spawning components. */
        public void configure(long creatureKey, LearningSettings settings) {
            perCreatureSettings.put(creatureKey, settings);
        }

        /** Returns the global settings (used as fallback or when no creature key is relevant). */
        public LearningSettings learningSettings() {
            return globalSettings;
        }

        /** Returns per-creature settings if registered, falling back to the global default. */
        public LearningSettings learningSettings(long creatureKey) {
            return perCreatureSettings.getOrDefault(creatureKey, globalSettings);
        }

        /** Called by CreatureActor.kill() to clean up the per-creature override. */
        public void releaseCreatureSettings(long creatureKey) {
            perCreatureSettings.remove(creatureKey);
        }
    }
}
