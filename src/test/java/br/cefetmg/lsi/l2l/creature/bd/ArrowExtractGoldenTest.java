package br.cefetmg.lsi.l2l.creature.bd;

import br.cefetmg.lsi.l2l.common.Constants;
import br.cefetmg.lsi.l2l.common.SequentialId;
import br.cefetmg.lsi.l2l.creature.arbiter.Arbiter;
import br.cefetmg.lsi.l2l.creature.arbiter.ArbitrationDecision;
import br.cefetmg.lsi.l2l.creature.arbiter.ExecutedEstimate;
import br.cefetmg.lsi.l2l.creature.arbiter.OperantEstimator;
import br.cefetmg.lsi.l2l.creature.common.Action;
import br.cefetmg.lsi.l2l.creature.common.ActionType;
import br.cefetmg.lsi.l2l.creature.common.Perception;
import br.cefetmg.lsi.l2l.world.FruitType;
import org.apache.arrow.vector.compression.CompressionUtil;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Cross-language golden test (docs/plans/arrow-ipc-write-path.md, PR 1 verification item 2):
 * writes a handful of fixed states via the real {@link ArrowIpcBackend}, runs the real
 * {@code python3 -m dl2l_data.extract} against that raw dir exactly as ansible's trial runner
 * does, then verifies the resulting Parquet output's row counts and values via a small pandas
 * check script - the analysis-contract gate confirming the write path and the (otherwise
 * untouched by this refactor - see extract.py's docstring) {@code tables.py} SQL still agree on
 * what a table means.
 *
 * <p>Skips (does not fail) when {@code python3}/{@code pyarrow}/{@code duckdb}/{@code pandas}
 * aren't importable, or the repo root can't be located from the test JVM's working directory -
 * this is an environment-dependent integration test, not a hard requirement for every {@code mvn
 * test} run on every machine (see {@code experiments/README.md}/{@code training/README.md} for
 * where these are actually provisioned - the three ansible-managed run environments, not
 * necessarily a bare contributor checkout).
 */
class ArrowExtractGoldenTest {

    private static Path repoRoot;
    private static boolean pythonDepsAvailable;

    @BeforeAll
    static void locateRepoRootAndCheckPythonDeps() throws IOException, InterruptedException {
        Path candidate = Path.of("").toAbsolutePath();
        for (int i = 0; i < 6 && candidate != null; i++, candidate = candidate.getParent()) {
            if (Files.isDirectory(candidate.resolve("scripts").resolve("dl2l_data"))
                    && Files.exists(candidate.resolve("pom.xml"))) {
                repoRoot = candidate;
                break;
            }
        }
        if (repoRoot == null) return;

        Process check = new ProcessBuilder("python3", "-c", "import duckdb, pyarrow, pandas")
                .redirectErrorStream(true)
                .start();
        pythonDepsAvailable = check.waitFor(30, TimeUnit.SECONDS) && check.exitValue() == 0;
    }

    private void assumeEnvironmentReady() {
        assumeTrue(repoRoot != null, "could not locate repo root (scripts/dl2l_data + pom.xml) from " + Path.of("").toAbsolutePath());
        assumeTrue(pythonDepsAvailable, "python3 with duckdb/pyarrow/pandas not available in this environment");
    }

    @Test
    void extractedParquetMatchesTheRawArrowWriteExactly(@TempDir Path tmp) throws Exception {
        assumeEnvironmentReady();

        Path rawDir = tmp.resolve("raw");
        Files.createDirectories(rawDir);
        Path outDir = tmp.resolve("out");
        Files.createDirectories(outDir);

        writeGoldenRawFixture(rawDir);

        runPythonOrFail(
                List.of("python3", "-m", "dl2l_data.extract",
                        "--experiment", "golden_test",
                        "--condition", "1_baseline",
                        "--out", outDir.toString(),
                        "--raw-dir", rawDir.toString(),
                        "--format", "parquet",
                        "--skip-backup"),
                Map.of("PYTHONPATH", repoRoot.resolve("scripts").toString()));

        Path creaturesParquet = outDir.resolve("1_baseline").resolve("creatures.parquet");
        assertTrue(Files.exists(creaturesParquet), "creatures.parquet must exist after extraction");

        String verifyScript = String.join("\n",
                "import pandas as pd, sys",
                "df = pd.read_parquet(sys.argv[1])",
                "assert len(df) == 2, f'expected 2 creatures, got {len(df)}'",
                "row = df[df['creature_key'] == 1].iloc[0]",
                "assert row['born_time'] == 1000, row['born_time']",
                "assert row['dead_time'] == 5000, row['dead_time']",
                "assert bool(row['gender']) is True, row['gender']",
                "assert abs(row['lifetime_s'] - 4.0) < 1e-9, row['lifetime_s']",
                "print('GOLDEN_OK')");
        Process verify = new ProcessBuilder("python3", "-c", verifyScript, creaturesParquet.toString())
                .redirectErrorStream(true)
                .start();
        String output = new String(verify.getInputStream().readAllBytes());
        boolean finished = verify.waitFor(30, TimeUnit.SECONDS);
        assertTrue(finished, "verification script timed out");
        assertEquals(0, verify.exitValue(), "verification script failed:\n" + output);
        assertTrue(output.contains("GOLDEN_OK"), "unexpected verification output:\n" + output);
    }

    /** A minimal but real-shaped raw dump: two creatures, born/died at known times. */
    private void writeGoldenRawFixture(Path rawDir) throws Exception {
        ArrowIpcBackend backend = new ArrowIpcBackend(rawDir, null, ArrowIpcBackend.DEFAULT_ALLOCATOR_LIMIT_MB,
                ArrowIpcBackend.DEFAULT_BATCH_ROWS, CompressionUtil.CodecType.NO_COMPRESSION);
        try {
            CreatureState first = new CreatureState(new SequentialId(1), true);
            first.setBornTime(1000L);
            first.setDeadTime(5000L);
            CreatureState second = new CreatureState(new SequentialId(2), false);
            second.setBornTime(2000L);
            second.setDeadTime(0L); // still alive

            backend.persistBatch(Map.of("creature_state", List.of(first, second)));
            backend.dumpToParquet();
        } finally {
            backend.close();
        }
    }

    private void runPythonOrFail(List<String> command, Map<String, String> extraEnv) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(command).redirectErrorStream(true);
        pb.environment().putAll(extraEnv);
        Process process = pb.start();
        String output = new String(process.getInputStream().readAllBytes());
        boolean finished = process.waitFor(60, TimeUnit.SECONDS);
        assertTrue(finished, "command timed out: " + command);
        assertEquals(0, process.exitValue(), "command failed: " + command + "\n" + output);
    }

    // ---- Issue #105: arbitration_decisions (join, optional raw tables, guards) -------------------

    private static final double[] H = {0.0, 0.0, 0.0, 0.0};
    private static final Perception APPLE = new Perception(FruitType.RED_APPLE, new SequentialId(1), 10, 0);

    private static Action on(ActionType type) {
        return new Action(type, APPLE);
    }

    /** A greedy decision whose pick is EAT (Q 1 against AVOID's Q 0): executed action EAT, no tie. */
    private static ArbitrationDecision greedyEat() {
        OperantEstimator e = new OperantEstimator(new double[] {50, 100}, 1.0);
        for (int i = 0; i < 20; i++) {
            e.record(Constants.HUNGER, on(ActionType.EAT), 2.0);
            e.record(Constants.HUNGER, on(ActionType.AVOID), 0.0);
        }
        return new Arbiter(List.of(new Arbiter.Controller(Arbiter.ControllerType.OPERANT, e)),
                new Arbiter.Params(0.8, 0.0, 0.0), new java.util.Random(1))
                .decide(List.of(on(ActionType.EAT), on(ActionType.AVOID)), H, Constants.HUNGER);
    }

    /** Creatures 1 and 2, cycles 1..4 each: one decision per cycle, all executed as EAT. */
    private static List<PersistenceState> arbitrationRows() {
        ArbitrationDecision d = greedyEat();
        List<PersistenceState> rows = new java.util.ArrayList<>();
        for (long key : new long[] {1L, 2L}) {
            for (long cycle = 1; cycle <= 4; cycle++) {
                rows.add(ArbitrationDecisionState.of(key, cycle - 1, 1000 + cycle, cycle, Constants.HUNGER, 0.25, d,
                        ExecutedEstimate.of(d, d.chosen(), a -> null)));
            }
        }
        return rows;
    }

    private static EpisodeReturnState step(long key, long seq, long episodeId, long cycle, String action) {
        return new EpisodeReturnState(key, seq, 2000L + cycle, episodeId, Constants.HUNGER, "REGULATED", cycle, 1L,
                action, null, 10.0, H.clone(), new double[] {-1, 0, 0, 0}, Double.NaN, 1.0);
    }

    /** Writes the creatures and the decision rows, plus the given episode steps, as a raw dump. */
    private void writeArbitrationDump(Path rawDir, List<PersistenceState> episodes) throws Exception {
        ArrowIpcBackend backend = new ArrowIpcBackend(rawDir, null, ArrowIpcBackend.DEFAULT_ALLOCATOR_LIMIT_MB,
                ArrowIpcBackend.DEFAULT_BATCH_ROWS, CompressionUtil.CodecType.NO_COMPRESSION);
        try {
            CreatureState first = new CreatureState(new SequentialId(1), true);
            first.setBornTime(1000L);
            CreatureState second = new CreatureState(new SequentialId(2), false);
            second.setBornTime(2000L);
            backend.persistBatch(Map.of("creature_state", List.of(first, second)));
            backend.persistBatch(Map.of("arbitration_decision_state", arbitrationRows()));
            if (!episodes.isEmpty()) {
                backend.persistBatch(Map.of("episode_return", episodes));
            }
            backend.dumpToParquet();
        } finally {
            backend.close();
        }
    }

    private String extractOrFail(Path rawDir, Path outDir) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder("python3", "-m", "dl2l_data.extract",
                "--experiment", "golden_test", "--condition", "1_baseline", "--out", outDir.toString(),
                "--raw-dir", rawDir.toString(), "--format", "parquet", "--skip-backup")
                .redirectErrorStream(true);
        pb.environment().put("PYTHONPATH", repoRoot.resolve("scripts").toString());
        Process process = pb.start();
        String output = new String(process.getInputStream().readAllBytes());
        assertTrue(process.waitFor(60, TimeUnit.SECONDS), "extraction timed out");
        assertEquals(0, process.exitValue(), "extraction failed:\n" + output);
        return output;
    }

    private void verifyWithPython(String script, Path file) throws IOException, InterruptedException {
        Process verify = new ProcessBuilder("python3", "-c", script, file.toString()).redirectErrorStream(true).start();
        String output = new String(verify.getInputStream().readAllBytes());
        assertTrue(verify.waitFor(30, TimeUnit.SECONDS), "verification timed out");
        assertEquals(0, verify.exitValue(), "verification failed:\n" + output);
        assertTrue(output.contains("ARB_OK"), output);
    }

    @Test
    void arbitrationDecisionsJoinTheEpisodeStepOnCreatureAndCycle(@TempDir Path tmp) throws Exception {
        assumeEnvironmentReady();
        Path rawDir = tmp.resolve("raw");
        Files.createDirectories(rawDir);
        Path outDir = tmp.resolve("out");
        Files.createDirectories(outDir);
        // Creature 1 at cycles 2 and 3; creature 2 at cycle 2 (same cycle, other creature: the key includes creature_key).
        writeArbitrationDump(rawDir, List.of(step(1L, 0L, 10L, 2L, "EAT"), step(1L, 1L, 10L, 3L, "EAT"),
                step(2L, 0L, 20L, 2L, "EAT")));
        extractOrFail(rawDir, outDir);

        verifyWithPython(String.join("\n",
                "import pandas as pd, sys",
                "df = pd.read_parquet(sys.argv[1])",
                "assert len(df) == 8, len(df)",
                "assert 'episode_action' not in df.columns",
                "m = df[df.episode_id.notna()]",
                "assert sorted(zip(m.creature_key, m.cycle)) == [(1, 2), (1, 3), (2, 2)], sorted(zip(m.creature_key, m.cycle))",
                "assert df.loc[df.episode_id.isna(), 'realised_value'].isna().all()",
                "assert m.loc[m.creature_key == 1, 'episode_id'].tolist() == [10, 10]",
                "print('ARB_OK')"),
                outDir.resolve("1_baseline").resolve("arbitration_decisions.parquet"));
    }

    @Test
    void anOldDumpWithoutTheNewRawTablesIsExtractedAndTheTablesAreSkipped(@TempDir Path tmp) throws Exception {
        assumeEnvironmentReady();
        Path rawDir = tmp.resolve("raw");
        Files.createDirectories(rawDir);
        Path outDir = tmp.resolve("out");
        Files.createDirectories(outDir);
        writeArbitrationDump(rawDir, List.of(step(1L, 0L, 10L, 2L, "EAT")));
        Files.delete(rawDir.resolve("arbitration_decision_state.arrow"));
        Files.delete(rawDir.resolve("episode_return.arrow"));

        String output = extractOrFail(rawDir, outDir);

        assertTrue(output.contains("arbitration_decisions skipped"), output);
        assertTrue(output.contains("episode_returns skipped"), output);
        assertFalse(Files.exists(outDir.resolve("1_baseline").resolve("arbitration_decisions.parquet")));
        assertFalse(Files.exists(outDir.resolve("1_baseline").resolve("episode_returns.parquet")));
        assertTrue(Files.exists(outDir.resolve("1_baseline").resolve("creatures.parquet")));
    }

    @Test
    void aStepWhoseActionDisagreesWithTheDecisionWarnsAndItsEpisodeColumnsAreNulled(@TempDir Path tmp) throws Exception {
        assumeEnvironmentReady();
        Path rawDir = tmp.resolve("raw");
        Files.createDirectories(rawDir);
        Path outDir = tmp.resolve("out");
        Files.createDirectories(outDir);
        // Cycle 2 of creature 1 says AVOID, but the decision executed EAT: the cycle clocks diverged.
        writeArbitrationDump(rawDir, List.of(step(1L, 0L, 10L, 2L, "AVOID"), step(1L, 1L, 10L, 3L, "EAT")));

        String output = extractOrFail(rawDir, outDir);

        assertTrue(output.contains("WARNING: arbitration_decisions: 1 joined row(s) disagree"), output);
        verifyWithPython(String.join("\n",
                "import pandas as pd, sys",
                "df = pd.read_parquet(sys.argv[1])",
                "assert len(df) == 8, len(df)",
                "bad = df[(df.creature_key == 1) & (df.cycle == 2)].iloc[0]",
                "assert pd.isna(bad['episode_id']) and pd.isna(bad['realised_value'])",
                "assert bad['executed_action'] == 'EAT'",
                "good = df[(df.creature_key == 1) & (df.cycle == 3)].iloc[0]",
                "assert good['episode_id'] == 10",
                "print('ARB_OK')"),
                outDir.resolve("1_baseline").resolve("arbitration_decisions.parquet"));
    }

    @Test
    void aDuplicateEpisodeStepDoesNotMultiplyTheDecisionRows(@TempDir Path tmp) throws Exception {
        assumeEnvironmentReady();
        Path rawDir = tmp.resolve("raw");
        Files.createDirectories(rawDir);
        Path outDir = tmp.resolve("out");
        Files.createDirectories(outDir);
        // Two steps at the same (creature, cycle): the first by seq is joined, the join stays one row per decision.
        writeArbitrationDump(rawDir, List.of(step(1L, 0L, 10L, 2L, "EAT"), step(1L, 1L, 99L, 2L, "EAT")));

        extractOrFail(rawDir, outDir);

        verifyWithPython(String.join("\n",
                "import pandas as pd, sys",
                "df = pd.read_parquet(sys.argv[1])",
                "assert len(df) == 8, len(df)",
                "assert df.creature_key.value_counts().to_dict() == {1: 4, 2: 4}",
                "assert df[(df.creature_key == 1) & (df.cycle == 2)]['episode_id'].tolist() == [10]",
                "print('ARB_OK')"),
                outDir.resolve("1_baseline").resolve("arbitration_decisions.parquet"));
    }
}
