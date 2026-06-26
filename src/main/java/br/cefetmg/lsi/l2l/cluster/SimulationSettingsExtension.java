package br.cefetmg.lsi.l2l.cluster;

import akka.actor.AbstractExtensionId;
import akka.actor.ActorSystem;
import akka.actor.Extension;
import akka.actor.ExtendedActorSystem;
import br.cefetmg.lsi.l2l.cluster.settings.LearningSettings;

/**
 * Akka Extension that makes the simulation's LearningSettings available to any actor
 * on this JVM node without passing them through every constructor.
 *
 * Initialise once per ActorSystem from Holder.preStart() before any creature is spawned:
 *   SimulationSettingsExtension.of(system).configure(simulation.getLearningSettings())
 *
 * Components read the settings in their preStart():
 *   LearningSettings s = SimulationSettingsExtension.of(context().system()).learningSettings();
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

        private volatile LearningSettings settings = new LearningSettings();

        /** Called once by Holder before any creature actor is created. */
        public void configure(LearningSettings settings) {
            this.settings = settings;
        }

        public LearningSettings learningSettings() {
            return settings;
        }
    }
}
