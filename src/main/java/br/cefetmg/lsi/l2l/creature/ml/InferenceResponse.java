package br.cefetmg.lsi.l2l.creature.ml;

import java.io.Serializable;

public record InferenceResponse(float[] emotionDeltaPrediction) implements Serializable {}
