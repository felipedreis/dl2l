package br.cefetmg.lsi.l2l.creature.ml;

import ai.djl.MalformedModelException;
import ai.djl.engine.Engine;
import ai.djl.ndarray.NDArray;
import ai.djl.ndarray.NDList;
import ai.djl.ndarray.NDManager;
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
 * encoder → adapter → predictor → critic → MSE → backward → step path.
 *
 * This test catches DJL API mismatches (wrong input arity, inference-mode
 * errors, shape mismatches) before any simulation run.
 */
public class ConsolidationPipelineTest {

    private static final Logger log = Logger.getLogger(ConsolidationPipelineTest.class.getName());
    private static final List<String> MODEL_FILES = List.of(
            "species_encoder.pt", "species_predictor.pt",
            "species_critic.pt", "species_adapter.pt",
            "model_contract.json");

    private static Path modelDir;
    private static ModelContract contract;

    @BeforeAll
    static void extractModels() throws Exception {
        modelDir = Files.createTempDirectory("dl2l-test-models-");
        for (String f : MODEL_FILES) {
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
                + " emotion_dim=" + contract.emotionDim);
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private ZooModel<NDList, NDList> loadInference(String name)
            throws IOException, ModelNotFoundException, MalformedModelException {
        return Criteria.builder()
                .setTypes(NDList.class, NDList.class)
                .optModelPath(modelDir)
                .optModelName(name)
                .optEngine("PyTorch")
                .optTranslator(new NoopTranslator())
                .build()
                .loadModel();
    }

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

    private static DefaultTrainingConfig trainingConfig() {
        return new DefaultTrainingConfig(Loss.l2Loss())
                .optOptimizer(Adam.builder()
                        .optLearningRateTracker(Tracker.fixed(0.001f)).build());
    }

    private List<Engram> makeEngrams(int count) {
        List<Engram> engrams = new ArrayList<>();
        SequentialId base = new SequentialId(1L);
        for (int i = 0; i < count; i++) {
            SequentialId id = base.next();
            Perception perc = new Perception(
                    FruitType.RED_APPLE,
                    id,
                    /* distance */ 5.0 + i,
                    /* angle    */ Math.PI * i / count);
            engrams.add(new Engram(
                    ActionType.APPROACH,
                    id,
                    null,
                    perc,
                    /* layCycle        */ (long) i,
                    /* emotionDelta    */ 0.5 - 0.01 * i,
                    /* eligibility     */ Math.exp(-0.1 * i),
                    /* reinforcedCycle */ (long) i + 1));
        }
        return engrams;
    }

    // -----------------------------------------------------------------------
    // Tests
    // -----------------------------------------------------------------------

    /**
     * Verifies the encoder's forward signature accepts [n, input_dim] and
     * returns [n, latent_dim]. No gradient, pure inference.
     */
    @Test
    void encoderForwardShape() throws Exception {
        int n = 4;
        try (ZooModel<NDList, NDList> encoder = loadInference("species_encoder");
             ai.djl.inference.Predictor<NDList, NDList> predictor = encoder.newPredictor(new NoopTranslator());
             NDManager mgr = NDManager.newBaseManager()) {

            NDArray input = mgr.ones(new Shape(n, contract.inputDim));
            NDArray output = predictor.predict(new NDList(input)).singletonOrThrow();

            assertEquals(2, output.getShape().dimension(), "encoder output should be 2-D");
            assertEquals(n, output.getShape().get(0), "batch size must be preserved");
            assertEquals(contract.latentDim, output.getShape().get(1),
                    "encoder output dim must equal latent_dim");
            log.info("encoder output shape: " + output.getShape());
        }
    }

    /**
     * Verifies the adapter's forward signature accepts [n, latent_dim] and
     * returns a tensor of the same shape. This is the exact call the
     * MemoryConsolidator makes during sleep replay.
     */
    @Test
    void adapterForwardShape() throws Exception {
        int n = 4;
        try (ZooModel<NDList, NDList> adapter = loadTrainable("species_adapter");
             Trainer trainer = adapter.newTrainer(trainingConfig());
             NDManager mgr = NDManager.newBaseManager()) {

            NDArray z = mgr.ones(new Shape(n, contract.latentDim));
            NDArray adapted = trainer.forward(new NDList(z)).singletonOrThrow();

            assertEquals(2, adapted.getShape().dimension(), "adapter output should be 2-D");
            assertEquals(n, adapted.getShape().get(0), "batch size must be preserved");
            log.info("adapter output shape: " + adapted.getShape());
        }
    }

    /**
     * Exercises the full consolidation pipeline for one batch:
     *   encoder → adapter → predictor → critic → MSE(pred_delta, actual_delta) → backward → step
     *
     * Assertions: loss is a finite float, no exception thrown.
     */
    @Test
    void singleBatchTrainingRound() throws Exception {
        List<Engram> engrams = makeEngrams(16);
        int n = engrams.size();

        try (ZooModel<NDList, NDList> encModel  = loadTrainable("species_encoder");
             ZooModel<NDList, NDList> adaModel  = loadTrainable("species_adapter");
             ZooModel<NDList, NDList> predModel = loadTrainable("species_predictor");
             ZooModel<NDList, NDList> critModel = loadTrainable("species_critic");
             Trainer encT  = encModel.newTrainer(trainingConfig());
             Trainer adaT  = adaModel.newTrainer(trainingConfig());
             Trainer predT = predModel.newTrainer(trainingConfig());
             Trainer critT = critModel.newTrainer(trainingConfig());
             NDManager mgr = NDManager.newBaseManager()) {

            float[] percData   = new float[n * contract.inputDim];
            float[] actionData = new float[n * contract.actionDim];
            float[] targetData = new float[n * contract.emotionDim];
            float[] weights    = new float[n];

            for (int i = 0; i < n; i++) {
                Engram e = engrams.get(i);
                percData[i * contract.inputDim]     = (float) e.perception().distance;
                percData[i * contract.inputDim + 1] = (float) e.perception().angle;
                percData[i * contract.inputDim + 2] = (float) Math.sin(e.perception().angle);
                percData[i * contract.inputDim + 5] = 1f; // RED_APPLE
                actionData[i * contract.actionDim]  = 1f; // APPROACH = index 0
                float delta = (float) e.emotionDelta();
                Arrays.fill(targetData, i * contract.emotionDim, (i + 1) * contract.emotionDim, delta);
                weights[i] = (float) e.eligibility();
            }

            NDArray percInput   = mgr.create(percData,   new Shape(n, contract.inputDim));
            NDArray actionBatch = mgr.create(actionData, new Shape(n, contract.actionDim));
            NDArray target      = mgr.create(targetData, new Shape(n, contract.emotionDim));
            NDArray weightArr   = mgr.create(weights,    new Shape(n));

            float lossValue;
            try (GradientCollector gc = Engine.getInstance().newGradientCollector()) {
                NDArray z         = encT.forward(new NDList(percInput)).singletonOrThrow();
                NDArray adaptedZ  = adaT.forward(new NDList(z)).singletonOrThrow();
                NDArray nextZ     = predT.forward(new NDList(adaptedZ, actionBatch)).singletonOrThrow();
                NDArray predDelta = critT.forward(new NDList(nextZ, actionBatch)).singletonOrThrow();

                NDArray rawLoss     = adaT.getLoss().evaluate(new NDList(target), new NDList(predDelta));
                NDArray weightedLoss = rawLoss.mul(weightArr.mean());
                lossValue = weightedLoss.getFloat();
                gc.backward(weightedLoss);
            }
            adaT.step(); // only adapter parameters are updated

            log.info("singleBatchTrainingRound: loss=" + lossValue);
            assertTrue(Float.isFinite(lossValue), "loss must be finite, got " + lossValue);
        }
    }

    /**
     * Simulates a full sleep episode: multiple batches over a window of engrams,
     * verifying that loss tracking and adapter step both work across batches.
     */
    @Test
    void fullEpisodeMultipleBatches() throws Exception {
        int windowSize = 64;
        int batchSize  = 16;
        List<Engram> engrams = makeEngrams(windowSize);

        try (ZooModel<NDList, NDList> encModel  = loadTrainable("species_encoder");
             ZooModel<NDList, NDList> adaModel  = loadTrainable("species_adapter");
             ZooModel<NDList, NDList> predModel = loadTrainable("species_predictor");
             ZooModel<NDList, NDList> critModel = loadTrainable("species_critic");
             Trainer encT  = encModel.newTrainer(trainingConfig());
             Trainer adaT  = adaModel.newTrainer(trainingConfig());
             Trainer predT = predModel.newTrainer(trainingConfig());
             Trainer critT = critModel.newTrainer(trainingConfig());
             NDManager sessionMgr = NDManager.newBaseManager()) {

            int batchCount = 0;
            int start = 0;

            while (start < engrams.size()) {
                int end = Math.min(start + batchSize, engrams.size());
                List<Engram> batch = engrams.subList(start, end);
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
                    percData[i * contract.inputDim + 5] = 1f;
                    actionData[i * contract.actionDim]  = 1f; // APPROACH
                    float delta = (float) e.emotionDelta();
                    Arrays.fill(targetData, i * contract.emotionDim, (i + 1) * contract.emotionDim, delta);
                    weights[i] = (float) e.eligibility();
                }

                float lossValue;
                try (NDManager batchMgr = sessionMgr.newSubManager()) {
                    NDArray percInput   = batchMgr.create(percData,   new Shape(n, contract.inputDim));
                    NDArray actionBatch = batchMgr.create(actionData, new Shape(n, contract.actionDim));
                    NDArray target      = batchMgr.create(targetData, new Shape(n, contract.emotionDim));
                    NDArray weightArr   = batchMgr.create(weights,    new Shape(n));

                    try (GradientCollector gc = Engine.getInstance().newGradientCollector()) {
                        NDArray z         = encT.forward(new NDList(percInput)).singletonOrThrow();
                        NDArray adaptedZ  = adaT.forward(new NDList(z)).singletonOrThrow();
                        NDArray nextZ     = predT.forward(new NDList(adaptedZ, actionBatch)).singletonOrThrow();
                        NDArray predDelta = critT.forward(new NDList(nextZ, actionBatch)).singletonOrThrow();

                        NDArray rawLoss      = adaT.getLoss().evaluate(new NDList(target), new NDList(predDelta));
                        NDArray weighted     = rawLoss.mul(weightArr.mean());
                        lossValue = weighted.getFloat();
                        gc.backward(weighted);
                    }
                }
                adaT.step();

                log.info("batch " + batchCount + " loss=" + lossValue);
                assertTrue(Float.isFinite(lossValue), "loss at batch " + batchCount + " must be finite");
                batchCount++;
                start = end;
            }

            int expectedBatches = (int) Math.ceil((double) windowSize / batchSize);
            assertEquals(expectedBatches, batchCount, "expected " + expectedBatches + " batches");
            log.info("fullEpisodeMultipleBatches: " + batchCount + " batches completed");
        }
    }
}
