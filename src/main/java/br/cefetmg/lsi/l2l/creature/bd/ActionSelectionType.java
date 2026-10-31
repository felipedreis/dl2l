package br.cefetmg.lsi.l2l.creature.bd;

public enum ActionSelectionType {
	TARGET_DISTANCE, AFFORDANCE, MEMORY, RANDOM, SHORT_TERM_MEMORY, WORLD_MODEL, ACTION_TENDENCY,
	/**
	 * Issue #104: a label, not a filter. Persisted when the arbiter selector decided; it is never in
	 * {@code enabledFilters} and never built as an {@code ActionFilter}.
	 */
	ARBITER
}
