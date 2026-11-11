package br.cefetmg.lsi.l2l.creature.arbiter;

/**
 * The state of an operant cell right after one sample was recorded into it, so the caller can log learning.
 *
 * @param n        decayed sample count of the cell
 * @param estimate the cell's {@code (Q, SE, sigma)}
 */
public record OperantUpdate(double n, Estimate estimate) {
}
