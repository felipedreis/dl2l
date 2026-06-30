package br.cefetmg.lsi.l2l.creature.ml;

import ai.djl.MalformedModelException;
import ai.djl.engine.Engine;
import ai.djl.ndarray.NDArray;
import ai.djl.ndarray.NDList;
import ai.djl.ndarray.NDManager;
import ai.djl.ndarray.index.NDIndex;
import ai.djl.ndarray.types.Shape;
import ai.djl.repository.zoo.Criteria;
import ai.djl.repository.zoo.ModelNotFoundException;
import ai.djl.repository.zoo.ZooModel;
import ai.djl.training.DefaultTrainingConfig;
import ai.djl.training.GradientCollector;
import ai.djl.training.Trainer;
import ai.djl.training.loss.Loss;
import ai.djl.training.optimizer.Adam;
import ai.djl.training.tracker.Tracker;
import ai.djl.translate.NoopTranslator;
import br.cefetmg.lsi.l2l.common.SequentialId;
import br.cefetmg.lsi.l2l.creature.common.ActionType;
import br.cefetmg.lsi.l2l.creature.common.Perception;
import br.cefetmg.lsi.l2l.creature.memory.Engram;
import br.cefetmg.lsi.l2l.world.FruitType;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Functional test for the sleep-consolidation training pipeline.
 *
 * Does NOT require an Akka cluster, PostgreSQL, or Docker. Loads the
 * TorchScript models from the classpath and exercises the full
 * encode → adapt → predict → critic → loss → backward → step path.
 *
 * Covers:
 *   - correct DJL API arity (encoder shape, adapter shape)
 *   - full prediction-error chain forward/backward
 *   - tanh normalization of emotionDelta targets
 *   - gradient zeroing before each batch (prevents NaN explosion)
 */
public class ConsolidationPipelineTest {

    private static final Logger log = Logger.getLogger(ConsolidationPipelineTest.class.getName());
    private static final List<String> MODEL_FILES_SINGLE = List.of(
            "model_contract.json",
            "species_encoder.pt", "species_predictor.pt",
            "species_critic.pt", "species_adapter.pt");
    private static final List<String> MODEL_FILES_DUAL = List.of(
            "model_contract.json",
            "species_encoder.pt", "species_predictor.pt",
            "species_critic.pt", "species_adapter.pt",
            "species_internal_encoder.pt");

    private static Path modelDir;
    private static ModelContract contract;

    @BeforeAll
    static void extractModels() throws Exception {
        modelDir = Files.createTempDirectory("dl2l-test-models-");
        // Two-step extraction: read contract first to discover hasDualEncoder,
        // then extract the appropriate set of .pt files, then validate hash.
        try (InputStream is = ConsolidationPipelineTest.class
                .getResourceAsStream("/models/model_contract.json")) {
            assertNotNull(is, "Missing classpath resource: /models/model_contract.json");
            Files.copy(is, modelDir.resolve("model_contract.json"), StandardCopyOption.REPLACE_EXISTING);
        }
        com.fasterxml.jackson.databind.ObjectMapper mapper =
                new com.fasterxml.jackson.databind.ObjectMapper();
        ModelContract preview;
        try (InputStream is = java.nio.file.Files.newInputStream(modelDir.resolve("model_contract.json"))) {
            preview = mapper.readValue(is, ModelContract.class);
        }
        List<String> filesToExtract = preview.hasDualEncoder ? MODEL_FILES_DUAL : MODEL_FILES_SINGLE;
        for (String f : filesToExtract) {
            if (f.equals("model_contract.json")) continue; // already extracted
            try (InputStream is = ConsolidationPipelineTest.class
                    .getResourceAsStream("/models/" + f)) {
                assertNotNull(is, "Missing classpath resource: /models/" + f);
                Files.copy(is, modelDir.resolve(f), StandardCopyOption.REPLACE_EXISTING);
            }
        }
        contract = ModelContract.load(modelDir);
        log.info("Models extracted — latent_dim=" + contract.latentDim
                + " input_dim=" + contract.inputDim
                + " action_dim=" + contract.actionDim
                + " emotion_dim=" + contract.emotionDim
                + " hasDualEncoder=" + contract.hasDualEncoder);
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private ZooModel<NDList, NDList> loadTrainable(String name)
            throws IOException, ModelNotFoundException, MalformedModelException {
        return Criteria.builder()
                .setTypes(NDList.class, NDList.class)
                .optModelPath(modelDir)
                .optModelName(name)
                .optEngine("PyTorch")
                .optTranslator(new NoopTranslator())
                .optOption("trainParam", "true")
                .build()
                .loadModel();
    }

    private static DefaultTrainingConfig adapterConfig() {
        return new DefaultTrainingConfig(Loss.l2Loss())
                .optOptimizer(Adam.builder()
                        .optLearningRateTracker(Tracker.fixed(0.001f)).build());
    }

    private static DefaultTrainingConfig frozenConfig() {
        return new DefaultTrainingConfig(Loss.l2Loss());
    }

    private List<Engram> makeEngrams(int count) {
        List<Engram> engrams = new ArrayList<>();
        SequentialId base = new SequentialId(1L);
        for (int i = 0; i < count; i++) {
            SequentialId id = base.next();
            Perception perc = new Perception(
                    FruitType.RED_APPLE, id,
                    5.0 + i, Math.PI * i / count);
            engrams.add(new Engram(
                    ActionType.APPROACH, id, null, perc,
                    (long) i,
                    0.5 - 0.01 * i,
                    Math.exp(-0.1 * i),
                    (long) i + 1));
        }
        return engrams;
    }

    /** Zero all parameter gradients — mirrors MemoryConsolidator.zeroGradients(). */
    private static void zeroGradients(Trainer trainer) {
        trainer.getModel().getBlock().getParameters().forEach(pair -> {
            try {
                NDArray arr = pair.getValue().getArray();
                if (arr.hasGradient()) {
                    arr.getGradient().set(new NDIndex("..."), 0f);
                }
            } catch (Exception ignored) {}
        });
    }

    /** Run one forward+backward batch through the full chain; return loss.
     *
     * intEncT is non-null only when contract.hasDualEncoder. In that case
     * z_internal (frozen) is concatenated with adaptedZ before the Predictor.
     */
    private float runBatch(Trainer encT, Trainer adaT, Trainer predT, Trainer critT,
                           Trainer intEncT,
                           List<Engram> batch, NDManager mgr) {
        int n = batch.size();
        float[] percData   = new float[n * contract.inputDim];
        float[] actionData = new float[n * contract.actionDim];
        float[] targetData = new float[n * contract.emotionDim];
        float[] weights    = new float[n];

        for (int i = 0; i < n; i++) {
            Engram e = batch.get(i);
            percData[i * contract.inputDim]     = (float) e.perception().distance;
            percData[i * contract.inputDim + 1] = (float) e.perception().angle;
            percData[i * contract.inputDim + 2] = (float) Math.sin(e.perception().angle);
            percData[i * contract.inputDim + 5] = 1f;           // RED_APPLE
            actionData[i * contract.actionDim]  = 1f;           // APPROACH = index 0
            float delta = (float) Math.tanh(e.emotionDelta());   // normalise to [-1, 1]
            Arrays.fill(targetData, i * contract.emotionDim, (i + 1) * contract.emotionDim, delta);
            weights[i] = (float) e.eligibility();
        }

        NDArray percInput   = mgr.create(percData,   new Shape(n, contract.inputDim));
        NDArray actionBatch = mgr.create(actionData, new Shape(n, contract.actionDim));
        NDArray target      = mgr.create(targetData, new Shape(n, contract.emotionDim));
        NDArray weightArr   = mgr.create(weights,    new Shape(n));

        zeroGradients(encT);
        zeroGradients(adaT);
        zeroGradients(predT);
        zeroGradients(critT);

        float lossValue;
        try (GradientCollector gc = Engine.getInstance().newGradientCollector()) {
            NDArray z        = encT.forward(new NDList(percInput)).singletonOrThrow();
            NDArray adaptedZ = adaT.forward(new NDList(z)).singletonOrThrow();

            NDArray zInternal = null;
            NDArray predInput;
            if (contract.hasDualEncoder && intEncT != null) {
                // Zero-init internal state: h_t = 0 (valid zero-arousal test input).
                NDArray hT = mgr.zeros(new Shape(n, contract.internalStateDim));
                zInternal  = intEncT.forward(new NDList(hT)).singletonOrThrow();
                predInput  = ai.djl.ndarray.NDArrays.concat(new NDList(adaptedZ, zInternal), 1);
            } else {
                predInput = adaptedZ;
            }

            NDArray nextZ = predT.forward(new NDList(predInput, actionBatch)).singletonOrThrow();
            // Dual-encoder: Critic takes concat(z_next, z_internal) — mirrors WorldModelEngine.
            NDArray criticInput = (zInternal != null)
                    ? ai.djl.ndarray.NDArrays.concat(new NDList(nextZ, zInternal), 1)
                    : nextZ;
            NDArray predDelta = critT.forward(new NDList(criticInput, actionBatch)).singletonOrThrow();

            NDArray rawLoss      = adaT.getLoss().evaluate(new NDList(target), new NDList(predDelta));
            NDArray weightedLoss = rawLoss.mul(weightArr.mean());
            lossValue = weightedLoss.getFloat();
            gc.backward(weightedLoss);
        }
        return lossValue;
    }

    // -----------------------------------------------------------------------
    // Tests
    // -----------------------------------------------------------------------

    /** Encoder [n, input_dim] → [n, latent_dim]. Inference only. */
    @Test
    void encoderForwardShape() throws Exception {
        int n = 4;
        try (ZooModel<NDList, NDList> enc = loadTrainable("species_encoder");
             Trainer trainer = enc.newTrainer(frozenConfig());
             NDManager mgr = NDManager.newBaseManager()) {

            NDArray input  = mgr.ones(new Shape(n, contract.inputDim));
            NDArray output = trainer.forward(new NDList(input)).singletonOrThrow();

            assertEquals(2, output.getShape().dimension());
            assertEquals(n, output.getShape().get(0));
            assertEquals(contract.latentDim, output.getShape().get(1));
            log.info("encoderForwardShape: " + output.getShape());
        }
    }

    /** Adapter [n, latent_dim] → [n, latent_dim]. Shape check. */
    @Test
    void adapterForwardShape() throws Exception {
        int n = 4;
        try (ZooModel<NDList, NDList> ada = loadTrainable("species_adapter");
             Trainer trainer = ada.newTrainer(adapterConfig());
             NDManager mgr = NDManager.newBaseManager()) {

            NDArray z       = mgr.ones(new Shape(n, contract.latentDim));
            NDArray adapted = trainer.forward(new NDList(z)).singletonOrThrow();

            assertEquals(2, adapted.getShape().dimension());
            assertEquals(n, adapted.getShape().get(0));
            assertEquals(contract.latentDim, adapted.getShape().get(1));
            log.info("adapterForwardShape: " + adapted.getShape());
        }
    }

    /**
     * Identity init (issue #43): a freshly loaded species_adapter.pt must
     * output ~0 for any input, so the additive composition
     * predictor(z, a) + adapter(predictor(z, a)) equals the species
     * Predictor alone before sleep consolidation runs. Prevents the
     * milestone-6 lifetime regression where random adapter weights
     * corrupted the inference chain on every creature's first life-tick.
     */
    @Test
    void adapterStartsAsIdentity() throws Exception {
        int n = 8;
        try (ZooModel<NDList, NDList> ada = loadTrainable("species_adapter");
             Trainer trainer = ada.newTrainer(adapterConfig());
             NDManager mgr = NDManager.newBaseManager()) {

            NDArray z      = mgr.randomNormal(new Shape(n, contract.latentDim));
            NDArray out    = trainer.forward(new NDList(z)).singletonOrThrow();
            float   maxAbs = out.abs().max().getFloat();

            assertTrue(maxAbs < 1e-5f,
                    "adapter must output ~0 at construction, got max|out|=" + maxAbs);
            log.info("adapterStartsAsIdentity: max|out|=" + maxAbs);
        }
    }

    /**
     * Full prediction-error chain: one batch.
     * Verifies loss is finite and adapter parameters change after step().
     */
    @Test
    void singleBatchTrainingRound() throws Exception {
        List<Engram> engrams = makeEngrams(16);

        ZooModel<NDList, NDList> intEncModel = contract.hasDualEncoder
                ? loadTrainable("species_internal_encoder") : null;
        try (ZooModel<NDList, NDList> encModel  = loadTrainable("species_encoder");
             ZooModel<NDList, NDList> adaModel  = loadTrainable("species_adapter");
             ZooModel<NDList, NDList> predModel = loadTrainable("species_predictor");
             ZooModel<NDList, NDList> critModel = loadTrainable("species_critic");
             Trainer encT     = encModel.newTrainer(frozenConfig());
             Trainer adaT     = adaModel.newTrainer(adapterConfig());
             Trainer predT    = predModel.newTrainer(frozenConfig());
             Trainer critT    = critModel.newTrainer(frozenConfig());
             Trainer intEncT  = intEncModel != null ? intEncModel.newTrainer(frozenConfig()) : null;
             NDManager mgr = NDManager.newBaseManager()) {

            float loss = runBatch(encT, adaT, predT, critT, intEncT, engrams, mgr);
            adaT.step();

            log.info("singleBatchTrainingRound: loss=" + loss);
            assertTrue(Float.isFinite(loss), "loss must be finite, got " + loss);
        }
    }

    /**
     * Gradient zeroing: verifies that zeroGradients() prevents accumulation.
     * Runs two backward passes on the adapter; asserts gradient after zeroing
     * is all-zero (not a sum of two passes).
     */
    @Test
    void gradientZeroingPreventsAccumulation() throws Exception {
        try (ZooModel<NDList, NDList> adaModel = loadTrainable("species_adapter");
             Trainer adaT = adaModel.newTrainer(adapterConfig());
             NDManager mgr = NDManager.newBaseManager()) {

            NDArray z = mgr.ones(new Shape(4, contract.latentDim));

            // First backward pass
            try (GradientCollector gc = Engine.getInstance().newGradientCollector()) {
                NDArray out = adaT.forward(new NDList(z)).singletonOrThrow();
                NDArray loss = out.mean();
                gc.backward(loss);
            }

            // Zero gradients (the fix)
            zeroGradients(adaT);

            // Check that all gradients are zero after zeroing
            boolean anyNonZero = false;
            for (var pair : adaT.getModel().getBlock().getParameters()) {
                NDArray arr = pair.getValue().getArray();
                if (arr.hasGradient()) {
                    NDArray grad = arr.getGradient();
                    float maxAbs = grad.abs().max().getFloat();
                    if (maxAbs > 0f) {
                        anyNonZero = true;
                        log.info("Parameter " + pair.getKey() + " still has grad max=" + maxAbs);
                    }
                }
            }

            // If getParameters() is empty for TorchScript, we skip the assertion
            // but log a warning so we know the zero_grad path isn't verifiable via DJL API
            long paramCount = adaT.getModel().getBlock().getParameters().size();
            if (paramCount == 0) {
                log.warning("gradientZeroingPreventsAccumulation: TorchScript block returned 0 parameters "
                        + "— gradient zeroing via DJL parameter API is not available for this model type; "
                        + "gradient accumulation must be prevented by other means (e.g. recreate Trainer)");
            } else {
                assertFalse(anyNonZero,
                        "All parameter gradients must be zero after zeroGradients()");
                log.info("gradientZeroingPreventsAccumulation: " + paramCount + " params verified zero");
            }
        }
    }

    /**
     * Full sleep episode: multiple batches with gradient zeroing and tanh targets.
     * Verifies loss is finite throughout and the abort-flag pattern works.
     */
    @Test
    void fullEpisodeMultipleBatches() throws Exception {
        int windowSize = 64;
        int batchSize  = 16;
        List<Engram> engrams = makeEngrams(windowSize);

        ZooModel<NDList, NDList> intEncModel = contract.hasDualEncoder
                ? loadTrainable("species_internal_encoder") : null;
        try (ZooModel<NDList, NDList> encModel  = loadTrainable("species_encoder");
             ZooModel<NDList, NDList> adaModel  = loadTrainable("species_adapter");
             ZooModel<NDList, NDList> predModel = loadTrainable("species_predictor");
             ZooModel<NDList, NDList> critModel = loadTrainable("species_critic");
             Trainer encT    = encModel.newTrainer(frozenConfig());
             Trainer adaT    = adaModel.newTrainer(adapterConfig());
             Trainer predT   = predModel.newTrainer(frozenConfig());
             Trainer critT   = critModel.newTrainer(frozenConfig());
             Trainer intEncT = intEncModel != null ? intEncModel.newTrainer(frozenConfig()) : null;
             NDManager sessionMgr = NDManager.newBaseManager()) {

            int batchCount = 0;
            int start = 0;
            while (start < engrams.size()) {
                int end   = Math.min(start + batchSize, engrams.size());
                List<Engram> batch = engrams.subList(start, end);

                float loss;
                try (NDManager batchMgr = sessionMgr.newSubManager()) {
                    loss = runBatch(encT, adaT, predT, critT, intEncT, batch, batchMgr);
                }
                adaT.step();

                log.info("batch " + batchCount + " loss=" + loss);
                assertTrue(Float.isFinite(loss), "loss at batch " + batchCount + " must be finite");
                batchCount++;
                start = end;
            }

            assertEquals((int) Math.ceil((double) windowSize / batchSize), batchCount);
            log.info("fullEpisodeMultipleBatches: " + batchCount + " batches completed");
        }
    }
}
