"""Registry of the per-condition table extraction queries.

Lifted verbatim from scripts/exp_extract.py. Each entry in TABLES maps a
table name (also the output filename stem) to a (sql, post_process) pair.
`post_process`, if not None, is called as `post_process(rows)` on the raw
psql_copy() rows (list-of-lists, first row = header) and must return rows
in the same shape.
"""

TABLES = {
    # `lifetime_s` is NULL for a creature still alive when the run hit maxRuntimeMinutes, and
    # every consumer drops NaNs — so survivors vanish from any lifetime statistic. That is
    # right-censoring treated as missing data, and it biases in the worst possible direction:
    # the arm that survives best loses the most observations, so its mean lifetime is dragged
    # down toward the arm that dies young. In the p84 v3 campaign two *_nomem arms had ZERO
    # deaths, which left P4's survival ratio with no denominator at all.
    #
    # `died` and `observed_s` make the censoring explicit so survival analysis can use it:
    # observed_s is the lifetime for a creature that died, and the follow-up time (last
    # recorded activity minus birth) for one that did not. Together they are the (time, event)
    # pair Kaplan-Meier and log-rank need. `lifetime_s` is kept unchanged so nothing that
    # already reads it changes meaning.
    "creatures": (
        """
        WITH last_seen AS (
            SELECT key AS creature_key, MAX(time) AS last_time
            FROM data.change_stimulus_state
            GROUP BY key
        )
        SELECT c.creature_key, c.creature_sequential,
               c.borntime  AS born_time,
               c.deadtime  AS dead_time,
               CASE WHEN c.deadtime > 0
                    THEN (c.deadtime - c.borntime) / 1000.0
                    ELSE NULL
               END AS lifetime_s,
               c.deadtime > 0 AS died,
               CASE WHEN c.deadtime > 0
                    THEN (c.deadtime - c.borntime) / 1000.0
                    ELSE (COALESCE(l.last_time, c.borntime) - c.borntime) / 1000.0
               END AS observed_s,
               c.gender
        FROM data.creature_state c
        LEFT JOIN last_seen l ON l.creature_key = c.creature_key
        ORDER BY c.creature_key
        """,
        None,
    ),
    "actions": (
        """
        SELECT css.key      AS creature_key,
               css.time,
               cas.action   AS action_type,
               cas.actionselectiontype AS selection_type,
               cas.key      AS target_key,
               COALESCE(cas.inference_duration_ms, 0) AS inference_ms,
               cas.focal_drive
        FROM data.chosen_action_state cas
        JOIN data.change_stimulus_state css
          ON cas.changestimulusstate_id = css.id
        ORDER BY css.key, css.time
        """,
        None,
    ),
    # Issue #100: per-drive outcome vector K, one row per regulation event with a non-zero
    # change, attributed to the originating action (BASELINE = no deliberate act). Rows are
    # additive: SUM(d_*) over any window is that window's change in each drive from regulation.
    "drive_outcomes": (
        """
        SELECT d.creature_key,
               d.seq,
               css.time,
               d.action,
               d.object_type,
               d.key        AS target_key,
               d.d_hunger, d.d_sleep, d.d_pain, d.d_tedium
        FROM data.drive_outcome_state d
        JOIN data.change_stimulus_state css
          ON d.changestimulusstate_id = css.id
        ORDER BY d.creature_key, d.seq
        """,
        None,
    ),
    # Issue #110: one row per closed decision of an episode, with its discounted return G_t per drive
    # (g_*), the drive levels at decision (h_*), the bootstrap term (NULL unless pre-empted) and the value
    # V(G | H) that was written to the operant table. Ordered per creature in write order.
    "episode_returns": (
        """
        SELECT creature_key,
               seq,
               time_ms,
               episode_id,
               drive,
               close_type,
               step_cycle,
               steps_to_close,
               action,
               object_type,
               distance,
               h_hunger, h_sleep, h_pain, h_tedium,
               g_hunger, g_sleep, g_pain, g_tedium,
               bootstrap,
               value
        FROM data.episode_return
        ORDER BY creature_key, seq
        """,
        None,
    ),
    "drives": (
        """
        SELECT css.key  AS creature_key,
               css.time,
               es_i.hunger_arausal    AS init_hunger,
               es_i.sleep_arausal     AS init_sleep,
               es_i.apathy_arausal    AS init_apathy,
               es_i.stress_arausal    AS init_stress,
               es_i.pain_arausal      AS init_pain,
               es_i.tedium_arausal    AS init_tedium,
               es_i.fear_arausal      AS init_fear,
               es_i.curiosity_arausal AS init_curiosity,
               es_i.fertility_arausal AS init_fertility,
               es_f.hunger_arausal    AS final_hunger,
               es_f.sleep_arausal     AS final_sleep,
               es_f.apathy_arausal    AS final_apathy,
               es_f.stress_arausal    AS final_stress,
               es_f.pain_arausal      AS final_pain,
               es_f.tedium_arausal    AS final_tedium,
               es_f.fear_arausal      AS final_fear,
               es_f.curiosity_arausal AS final_curiosity,
               es_f.fertility_arausal AS final_fertility
        FROM data.internal_dynamic_state ids
        JOIN data.change_stimulus_state css
          ON ids.changestimulusstate_id    = css.id
        JOIN data.emotional_state es_i
          ON ids.initialemotionalstate_id  = es_i.id
        JOIN data.emotional_state es_f
          ON ids.finalemotionalstate_id    = es_f.id
        ORDER BY css.key, css.time
        """,
        None,
    ),
    "behavioural_efficiency": (
        """
        SELECT css.key                         AS creature_key,
               css.time,
               bes.complextask::text           AS is_complex,
               bes.behaviouralefficiency       AS efficiency,
               bes.numberofobjects             AS n_objects,
               -- Issue #85: objects actually perceived, before PartialAppraisal's synthetic
               -- Self fallback. n_objects counts the post-fallback list, so an empty cycle
               -- and a one-object cycle are both 1 there; n_perceived == 0 is the only way
               -- to identify an empty sensory field. Perception flip rate is then
               -- (df.n_perceived > 0).diff().abs().mean().
               bes.perceivedobjects            AS n_perceived
        FROM data.behavioural_efficiency_state bes
        JOIN data.change_stimulus_state css
          ON bes.changestimulusstate_id = css.id
        ORDER BY css.key, css.time
        """,
        None,
    ),
    "body_states": (
        """
        SELECT css.key    AS creature_key,
               css.time,
               bs.initialx AS init_x,  bs.initialy AS init_y,
               bs.finalx   AS final_x, bs.finaly   AS final_y,
               bs.speed
        FROM data.body_state bs
        JOIN data.change_stimulus_state css
          ON bs.stimulusstate_id = css.id
        ORDER BY css.key, css.time
        """,
        None,
    ),
    # object_key/object_sequential identify *which* object was seen/eaten, not just its
    # type. Both are already in the raw Arrow schema (TableSchemas' seqCols expansion) and
    # were simply not selected here; they let an analysis pair a first sighting of object X
    # with the later interaction with that same X (issue #84).
    "perceptions": (
        """
        SELECT css.key                    AS creature_key,
               css.time,
               oss.type                  AS object_type,
               oss.key                   AS object_key,
               oss.sequential            AS object_sequential,
               oss.distance, oss.angle, oss.direction
        FROM data.object_seen_state oss
        JOIN data.change_stimulus_state css
          ON oss.changestimulusstate_id = css.id
        ORDER BY css.key, css.time
        """,
        None,
    ),
    # Issue #85 (incidental gap): object_smelt_state has been written since the Nose was
    # added but never extracted, so smell-driven ProprioceptiveStimulus appeared in no
    # Parquet output and `perceptions` silently meant "vision only". ObjectSmeltState
    # carries no distance/angle - the Nose computes those into the OlfactoryStimulus it
    # emits, not into the persisted record - so this is object identity plus timing.
    "smell_perceptions": (
        """
        SELECT css.key                    AS creature_key,
               css.time,
               oss.objecttype            AS object_type,
               oss.smelltype             AS smell_type
        FROM data.object_smelt_state oss
        JOIN data.change_stimulus_state css
          ON oss.changestimulusstate_id = css.id
        ORDER BY css.key, css.time
        """,
        None,
    ),
    "mouth_interactions": (
        """
        SELECT css.key            AS creature_key,
               css.time,
               mis.type          AS interaction_type,
               mis.objecttype    AS object_type,
               mis.key           AS object_key,
               mis.sequential    AS object_sequential
        FROM data.mouth_interactions_state mis
        JOIN data.change_stimulus_state css
          ON mis.changestimulusstate_id = css.id
        ORDER BY css.key, css.time
        """,
        None,
    ),
    "sleep_episodes": (
        """
        SELECT creature_key, onset_cycle, wake_cycle, duration_ticks
        FROM data.sleep_episode_state
        ORDER BY creature_key, onset_cycle
        """,
        None,
    ),
    "neuromodulators": (
        """
        SELECT creature_key, seq, dopamine, serotonin, orexin, circadian_phase
        FROM data.neuromodulator_state_log
        ORDER BY creature_key, seq
        """,
        None,
    ),
    "endocrine": (
        """
        SELECT creature_key, seq, cortisol_tonic, stress_level
        FROM data.endocrine_state_log
        ORDER BY creature_key, seq
        """,
        None,
    ),
    "expectancy": (
        """
        SELECT creature_key, cycle, mode, drive, drive_level,
               target, action, expected, reward, rpe
        FROM data.expectancy_state
        ORDER BY creature_key, cycle
        """,
        None,
    ),
    "engrams": (
        """
        SELECT creature_key, action_type, object_type,
               drive, drive_level,
               lay_cycle, reinforced_cycle, cycle_gap,
               eligibility, emotion_delta
        FROM data.engram_state
        ORDER BY creature_key, reinforced_cycle
        """,
        None,
    ),
    "consolidation_episodes": (
        """
        SELECT creature_key, onset_cycle, engram_count,
               mean_eligibility, std_eligibility,
               batches_completed, aborted
        FROM data.consolidation_episode_stat
        ORDER BY creature_key, onset_cycle
        """,
        None,
    ),
    "consolidation_batches": (
        """
        SELECT creature_key, onset_cycle, batch_index, batch_size, loss
        FROM data.consolidation_batch_stat
        ORDER BY creature_key, onset_cycle, batch_index
        """,
        None,
    ),
    "memory_traces": (
        """
        SELECT creature_key, onset_cycle, engram_count, groups_consolidated
        FROM data.memory_trace_stat
        ORDER BY creature_key, onset_cycle
        """,
        None,
    ),
    # The operant conditioning table as it changed over the run (issue #84). Six rows per
    # reinforcement — one per action of the evaluated target; untouched targets are not
    # rewritten, so readers forward-fill per (creature_key, target).
    #
    # `probability` is the RAW stored value and its per-target sum is NOT conserved:
    # ActionProbability.varyProbability clamps at 0 while OperantConditioningActor applies
    # the compensating -delta/(n-1) to the others unconditionally. ActionProbabilityFilter
    # normalises at selection time, so analysis must plot p / sum(p) per (target, seq),
    # never this column directly.
    "conditioning": (
        """
        SELECT creature_key, seq, time_ms, cycle, target, action,
               probability, reinforced_action, delta
        FROM data.action_probability_state
        ORDER BY creature_key, seq, action
        """,
        None,
    ),
    # One row per consultation of the episodic-memory action filter (issue #84).
    # A row means memory was actually reached: ActionSelection stops as soon as a filter
    # narrows the candidates to one, so an earlier filter deciding alone writes nothing.
    # `decided` false = memory had no opinion and passed the candidates through, in which
    # case action/target are null and the scores NaN.
    "memory_decisions": (
        """
        SELECT creature_key, seq, time_ms, cycle,
               engram_window, candidates, objects, scored, returned,
               winning_score, runnerup_score, decided,
               object_type,
               key        AS target_key,
               sequential AS target_sequential
        FROM data.memory_decision_state
        ORDER BY creature_key, seq
        """,
        None,
    ),
}

# Issue #105: one row per arbiter decision (actionSelector = ARBITER), with the closed-episode step it became, if
# any. The join is on (creature_key, cycle = step_cycle). episode_return is first de-duplicated to one row per key,
# so the join never multiplies a decision. The join is an extraction-time view: the raw row carries no episode id
# (arrow tables are append-only, and the episode closes after the decision).
_ARBITRATION_JOINED = ["episode_id", "episode_drive", "close_type", "steps_to_close", "bootstrap", "realised_value"]


def _arbitration_post_process(df):
    """Warn and null, never abort: an exception here would skip every later table. Where the joined step's action
    differs from the decision's executed action, the cycle clocks diverged, so the joined columns are nulled on those
    rows (calibration then never reads a mismatched value). The decision columns are left intact."""
    import sys

    # Duplicate (creature_key, step_cycle) rows in episode_return mean an EpisodeBuffer invariant broke: the join keeps
    # the first of them, and says so.
    dup = df["episode_dup_n"].fillna(1) > 1
    n_dup = int(dup.sum())
    if n_dup:
        creatures = sorted(int(c) for c in df.loc[dup, "creature_key"].unique())
        print(f"  WARNING: arbitration_decisions: {n_dup} decision(s) matched several episode_return rows "
              f"(creatures {creatures}); the first row of each was kept", file=sys.stderr)

    mismatch = df["episode_action"].notna() & (df["episode_action"] != df["executed_action"])
    n = int(mismatch.sum())
    if n:
        creatures = sorted(int(c) for c in df.loc[mismatch, "creature_key"].unique())
        print(f"  WARNING: arbitration_decisions: {n} joined row(s) disagree with the episode's action "
              f"(creatures {creatures}); their episode columns are set to NULL", file=sys.stderr)
        # Integer columns go to the nullable Int64 first: assigning None into an int64 column would turn it into float64.
        for col in ("episode_id", "steps_to_close"):
            df[col] = df[col].astype("Int64")
        for col in _ARBITRATION_JOINED:
            df.loc[mismatch, col] = None
    return df.drop(columns=["episode_action", "episode_dup_n"])


TABLES["arbitration_decisions"] = (
    """
    SELECT d.creature_key, d.seq, d.time_ms, d.cycle, d.focal_drive, d.efficiency, d.affordable_controller,
           d.candidate_count, d.deciding_controller, d.n_consulted, d.source, d.n_tied_at_max,
           d.chosen_action, d.chosen_object_type, d.chosen_q, d.chosen_se, d.chosen_sigma,
           d.best_q, d.best_se, d.best_sigma, d.runnerup_q, d.runnerup_se, d.runnerup_sigma,
           d.executed_action, d.executed_object_type, d.executed_source, d.executed_q, d.executed_se, d.executed_sigma,
           d.operant_conf, d.operant_q, d.operant_se, d.operant_sigma,
           d.episodic_conf, d.episodic_q, d.episodic_se, d.episodic_sigma,
           d.planner_conf, d.planner_q, d.planner_se, d.planner_sigma,
           er.episode_id, er.drive AS episode_drive, er.close_type, er.steps_to_close,
           er.bootstrap, er.value AS realised_value,
           er.action AS episode_action, er.dup_n AS episode_dup_n
    FROM data.arbitration_decision_state d
    LEFT JOIN (
        SELECT DISTINCT ON (creature_key, step_cycle) *,
               COUNT(*) OVER (PARTITION BY creature_key, step_cycle) AS dup_n
        FROM data.episode_return
        ORDER BY creature_key, step_cycle, seq
    ) er ON er.creature_key = d.creature_key AND er.step_cycle = d.cycle
    ORDER BY d.creature_key, d.seq
    """,
    _arbitration_post_process,
)

# Run setup (issue #95): one row per holder JVM with the root seed. Absent from raw dumps
# written before that change, so extract.py skips it when the view does not exist.
# SELECT * on purpose: dumps from before the focus-tracker thresholds were configurable lack
# focus_theta_on / focus_theta_off, and extract.py must still read them.
TABLES["run_info"] = (
    """
    SELECT *
    FROM data.run_info
    ORDER BY started_at
    """,
    None,
)

# One row per update of the A2 operant table: the sample recorded into the (object_type, action) cell and the
# cell's n, Q, SE and sigma right after it (docs/plans/operant-per-outcome.md). Empty/absent unless
# operantEstimatorEnabled.
TABLES["operant_updates"] = (
    """
    SELECT creature_key, seq, time_ms, "cycle", object_type, action, sample, n, q, se, sigma
    FROM data.operant_update_state
    ORDER BY creature_key, seq
    """,
    None,
)

# Issue #111: one row per episodic engram (A3/A5): the closed step's decision-time drive levels h_*, return g_*,
# bootstrap term (0 unless pre-empted), steps to the outcome and the retrieval key k_00..k_14 (EngramKeyEncoder).
# Empty/absent unless episodicEstimatorEnabled.
TABLES["episodic_engrams"] = (
    """
    SELECT creature_key, seq, time_ms, episode_id, drive, close_type, step_cycle, steps_to_outcome, action,
           object_type, h_hunger, h_sleep, h_pain, h_tedium, g_hunger, g_sleep, g_pain, g_tedium, bootstrap_term,
           k_00, k_01, k_02, k_03, k_04, k_05, k_06, k_07, k_08, k_09, k_10, k_11, k_12, k_13, k_14
    FROM data.episodic_engram
    ORDER BY creature_key, seq
    """,
    None,
)

# Tables written before pg_dump/manifest bookkeeping, in the order exp_extract.py
# originally wrote them (a couple of analyses key off this ordering in logs).
TABLE_ORDER = [
    "creatures", "actions", "drives", "drive_outcomes", "behavioural_efficiency", "body_states",
    "perceptions", "smell_perceptions", "mouth_interactions", "sleep_episodes", "neuromodulators",
    "endocrine", "expectancy", "engrams", "consolidation_episodes",
    "consolidation_batches", "memory_traces", "conditioning", "memory_decisions",
    "episode_returns", "arbitration_decisions", "run_info", "operant_updates", "episodic_engrams",
]

assert set(TABLE_ORDER) == set(TABLES)
