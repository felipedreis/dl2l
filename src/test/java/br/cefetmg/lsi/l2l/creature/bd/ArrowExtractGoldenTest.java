package br.cefetmg.lsi.l2l.creature.bd;

import br.cefetmg.lsi.l2l.common.SequentialId;
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
}
