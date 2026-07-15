"""Registry of the per-condition table extraction queries.

Lifted verbatim from scripts/exp_extract.py. Each entry in TABLES maps a
table name (also the output filename stem) to a (sql, post_process) pair.
`post_process`, if not None, is called as `post_process(rows)` on the raw
psql_copy() rows (list-of-lists, first row = header) and must return rows
in the same shape.
"""

from .db import decode_type_hex


def _decode_object_type(rows: list) -> list:
    if len(rows) > 1:
        idx = rows[0].index("object_type")
        for row in rows[1:]:
            row[idx] = decode_type_hex(row[idx])
    return rows


TABLES = {
    "creatures": (
        """
        SELECT creature_key, creature_sequential,
               borntime  AS born_time,
               deadtime  AS dead_time,
               CASE WHEN deadtime > 0
                    THEN (deadtime - borntime) / 1000.0
                    ELSE NULL
               END AS lifetime_s,
               gender
        FROM data.creature_state
        ORDER BY creature_key
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
               COALESCE(cas.inference_duration_ms, 0) AS inference_ms
        FROM data.chosen_action_state cas
        JOIN data.change_stimulus_state css
          ON cas.changestimulusstate_id = css.id
        ORDER BY css.key, css.time
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
               bes.numberofobjects             AS n_objects
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
    "perceptions": (
        """
        SELECT css.key                    AS creature_key,
               css.time,
               encode(oss.type, 'hex')   AS object_type,
               oss.distance, oss.angle, oss.direction
        FROM data.object_seen_state oss
        JOIN data.change_stimulus_state css
          ON oss.changestimulusstate_id = css.id
        ORDER BY css.key, css.time
        """,
        _decode_object_type,
    ),
    "mouth_interactions": (
        """
        SELECT css.key            AS creature_key,
               css.time,
               mis.type          AS interaction_type,
               mis.objecttype    AS object_type
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
        SELECT creature_key, action_type,
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
}

# Tables written before pg_dump/manifest bookkeeping, in the order exp_extract.py
# originally wrote them (a couple of analyses key off this ordering in logs).
TABLE_ORDER = [
    "creatures", "actions", "drives", "behavioural_efficiency", "body_states",
    "perceptions", "mouth_interactions", "sleep_episodes", "neuromodulators",
    "endocrine", "expectancy", "engrams", "consolidation_episodes",
    "consolidation_batches", "memory_traces",
]

assert set(TABLE_ORDER) == set(TABLES)
