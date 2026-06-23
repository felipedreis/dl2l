package br.cefetmg.lsi.l2l.creature.ml;

import akka.actor.ActorRef;

import java.io.Serializable;

public record InferenceRequest(
        float[] perceptionFeatures,  // null when encodedLatent is provided (Epic 6+)
        float[] encodedLatent,       // pre-adapted latent from WorldModelFilter; null in Phase 5
        float[] actionOneHot,
        ActorRef replyTo
) implements Serializable {}
