-- DL2L persistence schema (issue #79 — replaces EclipseLink's runtime
-- `drop-and-create-tables` DDL generation now that JPA is gone from the write path).
--
-- Generated as a starting point from EclipseLink's own DDL output (captured via
-- eclipselink.ddl-generation.output-mode=sql-script against the still-JPA-annotated
-- entity classes), then hand-edited:
--   - every INTEGER surrogate `id` / foreign-key column -> uuid, always app-supplied
--     (java.util.UUID.randomUUID(), assigned at object construction — see BDActor) —
--     no DB-side generation, so no SEQUENCE table/bookkeeping is needed anymore.
--   - no FOREIGN KEY constraints: this is an append-only telemetry log, not a
--     transactional system: referential integrity comes from the app always
--     generating consistent UUIDs, not DB-enforced checks, and skipping constraint
--     checking is part of what makes unordered bulk COPY loading fast.
--   - object_seen_state.type / object_smelt_state.objecttype: was BYTEA (EclipseLink's
--     @Lob Java-serialization fallback for the WorldObjectType interface type) -> text
--     (the type's plain name, e.g. "RED_APPLE"), written directly by the app now.
--   - dropped mouth_state, mouth_state_mouth_interactions_state, memory_evocation_state:
--     dead entities, never constructed anywhere in the app and never read by
--     scripts/dl2l_data or analysis/ (confirmed by repo-wide grep).
--   - no secondary indexes beyond each table's primary key, matching the original
--     JPA-generated schema exactly (it had none either) — this keeps the write path
--     (COPY-loading) as fast as possible; add indexes later if Python-side extraction
--     read speed becomes the bottleneck instead.

CREATE SCHEMA IF NOT EXISTS data;

CREATE TABLE data.change_stimulus_state (
    id uuid NOT NULL PRIMARY KEY,
    componentclass varchar(255),
    time bigint,
    key bigint,
    sequential bigint
);

CREATE TABLE data.stimulus_state (
    id uuid NOT NULL PRIMARY KEY,
    stimulusclass varchar(255),
    type varchar(255),
    componentkey bigint,
    stimulusseq bigint,
    changestimulusemitted_id uuid,
    changestimulusreceived_id uuid
);

CREATE TABLE data.creature_state (
    id uuid NOT NULL PRIMARY KEY,
    borntime bigint,
    deadtime bigint,
    gender boolean,
    father_key bigint,
    father_sequential bigint,
    mother_key bigint,
    mother_sequential bigint,
    creature_key bigint,
    creature_sequential bigint
);

CREATE TABLE data.emotional_state (
    id uuid NOT NULL PRIMARY KEY,
    apathy_arausal float,
    curiosity_arausal float,
    fear_arausal float,
    fertility_arausal float,
    hunger_arausal float,
    pain_arausal float,
    sleep_arausal float,
    stress_arausal float,
    tedium_arausal float
);

CREATE TABLE data.internal_dynamic_state (
    id uuid NOT NULL PRIMARY KEY,
    changestimulusstate_id uuid,
    finalemotionalstate_id uuid,
    initialemotionalstate_id uuid
);

CREATE TABLE data.eye_state (
    id uuid NOT NULL PRIMARY KEY,
    finalopening float,
    finalstartangle float,
    initialopening float,
    initialstartangle float,
    changestimulusstate_id uuid
);

CREATE TABLE data.object_seen_state (
    id uuid NOT NULL PRIMARY KEY,
    angle float,
    direction float,
    distance float,
    type text,
    key bigint,
    sequential bigint,
    changestimulusstate_id uuid
);

CREATE TABLE data.mouth_interactions_state (
    id uuid NOT NULL PRIMARY KEY,
    objecttype varchar(255),
    type varchar(255),
    key bigint,
    sequential bigint,
    changestimulusstate_id uuid
);

CREATE TABLE data.nose_state (
    id uuid NOT NULL PRIMARY KEY,
    key bigint,
    sequential bigint
);

CREATE TABLE data.object_smelt_state (
    id uuid NOT NULL PRIMARY KEY,
    objecttype text,
    smelltype varchar(255),
    key bigint,
    sequential bigint,
    changestimulusstate_id uuid
);

CREATE TABLE data.chosen_action_state (
    id uuid NOT NULL PRIMARY KEY,
    action varchar(255),
    actionselectiontype varchar(255),
    inference_duration_ms bigint,
    key bigint,
    sequential bigint,
    changestimulusstate_id uuid
);

CREATE TABLE data.body_state (
    id uuid NOT NULL PRIMARY KEY,
    finalx float,
    finaly float,
    initialx float,
    initialy float,
    speed float,
    stimulusstate_id uuid
);

CREATE TABLE data.behavioural_efficiency_state (
    id uuid NOT NULL PRIMARY KEY,
    behaviouralefficiency float,
    complextask boolean,
    numberofobjects integer,
    changestimulusstate_id uuid
);

CREATE TABLE data.regulation_batch_stat (
    id uuid NOT NULL PRIMARY KEY,
    batchsize integer,
    drivestouchedmask integer,
    regulatingcount integer,
    samedrivecollision boolean,
    changestimulusstate_id uuid
);

CREATE TABLE data.engram_state (
    id uuid NOT NULL PRIMARY KEY,
    action_type varchar(255),
    creature_key bigint,
    cycle_gap bigint,
    eligibility float,
    emotion_delta float,
    lay_cycle bigint,
    reinforced_cycle bigint
);

CREATE TABLE data.sleep_episode_state (
    id uuid NOT NULL PRIMARY KEY,
    creature_key bigint,
    duration_ticks integer,
    onset_cycle bigint,
    wake_cycle bigint
);

CREATE TABLE data.consolidation_episode_stat (
    id uuid NOT NULL PRIMARY KEY,
    aborted boolean,
    batches_completed integer,
    creature_key bigint,
    engram_count integer,
    mean_eligibility float,
    onset_cycle bigint,
    std_eligibility float
);

CREATE TABLE data.consolidation_batch_stat (
    id uuid NOT NULL PRIMARY KEY,
    batch_index integer,
    batch_size integer,
    creature_key bigint,
    loss float,
    onset_cycle bigint
);

CREATE TABLE data.memory_trace_stat (
    id uuid NOT NULL PRIMARY KEY,
    creature_key bigint,
    engram_count integer,
    groups_consolidated integer,
    onset_cycle bigint
);

CREATE TABLE data.expectancy_state (
    id uuid NOT NULL PRIMARY KEY,
    action varchar(255),
    creature_key bigint,
    cycle bigint,
    drive varchar(255),
    drive_level float,
    expected float,
    mode varchar(255),
    reward float,
    rpe float,
    target varchar(255)
);

CREATE TABLE data.neuromodulator_state_log (
    id uuid NOT NULL PRIMARY KEY,
    circadian_phase float,
    creature_key bigint,
    dopamine float,
    orexin float,
    seq bigint,
    serotonin float
);

CREATE TABLE data.endocrine_state_log (
    id uuid NOT NULL PRIMARY KEY,
    cortisol_tonic float,
    creature_key bigint,
    seq bigint,
    stress_level float
);
