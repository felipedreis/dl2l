# Phase 0 — De-risk before building (Epic #9)

## Context

Before writing any world-model code we need to answer two questions:
1. **Does the Mode-1 policy's experience distribution cover enough of the state space** to train a species model on? (Task 0.1 — coverage probe)
2. **How do we detect at runtime when the model is out-of-distribution** so the filter can self-disable? (Task 0.2 — metric definition only; wired in Task 6.3)

No behavioural changes. Task 0.1 adds a read-only extractor + Python analysis script. Task 0.2 produces a written spec in the docs.

---

## Task 0.1 — Coverage probe

### 1. Add a `@NamedNativeQuery` to `ObjectSeenState.java`

File: `src/main/java/br/cefetmg/lsi/l2l/creature/bd/ObjectSeenState.java`

Add before the class declaration (mirror the pattern in `ChosenActionState.java`):

```java
@NamedNativeQueries({
    @NamedNativeQuery(name = "ObjectSeenState.getPerceptionsByCreature",
        query = "SELECT css.key as creature_key, oss.type as object_type, " +
                "oss.distance, oss.angle, css.time " +
                "FROM data.object_seen_state oss " +
                "JOIN data.change_stimulus_state css ON oss.changestimulusstate_id = css.id " +
                "WHERE css.key = ?")
})
```

`oss.type` is returned as raw `byte[]` (PostgreSQL bytea → JDBC). The extractor deserializes it with `ObjectInputStream` — see `PerceptionCoverageExtractor.deserializeTypeName`. A `CAST(type AS VARCHAR)` was tried first but produces the PostgreSQL `\x<hex>` textual encoding rather than the enum name.

### 2. Add `PerceptionCoverageExtractor.java`

File: `src/main/java/br/cefetmg/lsi/l2l/analysis/extractor/PerceptionCoverageExtractor.java`

Extends `SampleExtractor` (same as `TracingExtractor`). Iterates over all creature IDs, runs the named query, accumulates into ArrayLists, builds a `DataSet`.

Columns: `creatureKey` (Long), `objectType` (String), `distance` (Double), `angle` (Double), `time` (Long).

```java
public class PerceptionCoverageExtractor extends SampleExtractor {
    public PerceptionCoverageExtractor(EntityManager em, List<SequentialId> ids) {
        super(em, ids);
    }

    @Override
    public DataSet extract() {
        List<Long> creatureKeys = new ArrayList<>();
        List<String> objectTypes = new ArrayList<>();
        List<Double> distances   = new ArrayList<>();
        List<Double> angles      = new ArrayList<>();
        List<Long>   times       = new ArrayList<>();

        Query q = em.createNamedQuery("ObjectSeenState.getPerceptionsByCreature");
        for (SequentialId id : ids) {
            q.setParameter(1, id.key);
            for (Object[] row : (List<Object[]>) q.getResultList()) {
                creatureKeys.add((Long)   row[0]);
                objectTypes .add((String) row[1]);
                distances   .add((Double) row[2]);
                angles      .add((Double) row[3]);
                times       .add((Long)   row[4]);
            }
        }
        int n = creatureKeys.size();
        DataSet ds = new DataSet(n);
        ds.addSeries("creatureKey", creatureKeys.toArray(new Long[n]));
        ds.addSeries("objectType",  objectTypes .toArray(new String[n]));
        ds.addSeries("distance",    distances   .toArray(new Double[n]));
        ds.addSeries("angle",       angles      .toArray(new Double[n]));
        ds.addSeries("time",        times       .toArray(new Long[n]));
        return ds;
    }

    @Override public String getName() { return "perceptionCoverage"; }
}
```

### 3. Register in `RoutineCreator.sampleRoutine()`

File: `src/main/java/br/cefetmg/lsi/l2l/analysis/RoutineCreator.java`

Add `new PerceptionCoverageExtractor(em, ids)` to the `new Routine(...)` call in `sampleRoutine()`.

### 4. Python analysis script

File: `analysis/coverage_probe.py` (Python 3 + pandas + sklearn + matplotlib)

Convention: `wd` variable at the top points to the results directory. Uses `os.walk` to find all `perceptionCoverage.csv` files (same pattern as `util.py`'s `get_data_frames`).

Steps:
1. Concat all CSVs; drop rows with null `distance`/`angle`.
2. Print per-dimension stats: min/max/mean/std/p5/p95 for `distance` and `angle`.
3. Print per-`objectType` row counts and fraction.
4. One-hot encode `objectType`, build numeric matrix `[distance, angle, *onehot]`, run `sklearn.decomposition.PCA`.
5. Print explained-variance ratios; print how many components to reach 95% variance.
6. Save: `distance_hist.png`, `angle_hist.png`, `pca_scree.png`.
7. Print decision recommendation: if ≥ N object types have < 1% coverage → recommend adding random-policy episodes.

---

## Task 0.2 — Online prediction-error monitor (design only)

File: `docs/hld/prediction_error_monitor.md`

Write a short spec (no code, no Java), to be read by whoever implements Task 6.3. Contents:

- **Metric name**: `latentPredictionError`
- **Definition**: For each engram `(s_t, a_t, s_{t+1})` formed during waking, compute MSE between the species predictor's output `Pred(Enc(s_t), a_t)` and the actual next encoding `Enc(s_{t+1})` in latent space.
- **Rolling window**: exponential moving average over the last 100 engrams per creature.
- **Baseline**: median `latentPredictionError` on the held-out validation split from Task 2.3 (recorded in `model_contract.json`).
- **Self-disable threshold**: when rolling EMA > `2 × baseline`. WorldModelFilter returns possibilities unmodified for that cycle.
- **Wiring point**: `WorldModelFilter.doFilter()` in Task 6.3 reads the per-creature EMA, compares to the threshold from `model_contract.json`.

---

## Files modified / created

| Action | File |
|--------|------|
| Edit   | `src/main/java/br/cefetmg/lsi/l2l/creature/bd/ObjectSeenState.java` |
| Create | `src/main/java/br/cefetmg/lsi/l2l/analysis/extractor/PerceptionCoverageExtractor.java` |
| Edit   | `src/main/java/br/cefetmg/lsi/l2l/analysis/RoutineCreator.java` |
| Create | `analysis/coverage_probe.py` |
| Create | `docs/hld/prediction_error_monitor.md` |

---

## Verification

1. `mvn package` — must compile clean.
2. Run the simulation via Docker Compose (or scripts/holder.sh etc.) to populate DB.
3. Run the data extractor: `java -jar target/l2l-*-wd.jar --extractor --save <output-dir>` — should produce `perceptionCoverage.csv` in the output.
4. Set `wd` in `analysis/coverage_probe.py` and run it — should print stats, decision, and save 3 PNG files.
5. Confirm hunger-deprivation-over-time plot (from existing `ArousalHistoryExtractor`) is unchanged — i.e., no behavioural side-effects from the new extractor.
