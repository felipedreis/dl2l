package br.cefetmg.lsi.l2l.creature.ml;

import ai.djl.MalformedModelException;
import ai.djl.repository.zoo.Criteria;
import ai.djl.repository.zoo.ModelNotFoundException;
import ai.djl.repository.zoo.ZooModel;
import ai.djl.ndarray.NDList;
import ai.djl.translate.NoopTranslator;
import akka.actor.AbstractExtensionId;
import akka.actor.ActorRef;
import akka.actor.ActorSystem;
import akka.actor.Extension;
import akka.actor.ExtendedActorSystem;
import akka.actor.Props;
import akka.routing.RoundRobinPool;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Akka Extension that loads the species ML models exactly once per JVM node.
 * All creature actors must interact with the ML service exclusively through the
 * inferenceRouter ActorRef — never by holding a direct reference to the models.
 *
 * Usage: MLServiceExtension.get(system).inferenceRouter()
 */
public class MLServiceExtension extends AbstractExtensionId<MLServiceExtension.Impl> {

    public static final MLServiceExtension Id = new MLServiceExtension();

    public static Impl of(ActorSystem system) {
        return system.registerExtension(Id);
    }

    @Override
    public Impl createExtension(ExtendedActorSystem system) {
        return new Impl(system);
    }

    // -------------------------------------------------------------------------

    public record LoadedModels(
            ZooModel<NDList, NDList> encoder,
            ZooModel<NDList, NDList> predictor,          // null when hasUnifiedPredictor=true
            ZooModel<NDList, NDList> critic,             // null when hasUnifiedPredictor=true
            ZooModel<NDList, NDList> internalEncoder,    // null when hasDualEncoder=false
            ZooModel<NDList, NDList> unifiedPredictor,   // null when hasUnifiedPredictor=false
            ModelContract contract
    ) {
        public boolean hasDualEncoder() { return internalEncoder != null; }
        public boolean hasUnifiedPredictor() { return unifiedPredictor != null; }
    }

    // -------------------------------------------------------------------------

    public static class Impl implements Extension {

        private static final Logger logger = Logger.getLogger(Impl.class.getName());
        private static final List<String> MODEL_FILES_SINGLE = List.of(
                "species_encoder.pt", "species_predictor.pt",
                "species_critic.pt", "species_adapter.pt",
                "model_contract.json");
        private static final List<String> MODEL_FILES_DUAL = List.of(
                "species_encoder.pt", "species_predictor.pt",
                "species_critic.pt", "species_adapter.pt",
                "species_internal_encoder.pt", "model_contract.json");
        private static final List<String> MODEL_FILES_UNIFIED = List.of(
                "species_encoder.pt", "species_unified_predictor.pt",
                "species_adapter.pt", "species_internal_encoder.pt",
                "model_contract.json");

        private final LoadedModels models;
        private final ActorRef inferenceRouter;
        private final Path modelDir;
        private final ExecutorService trainingExecutor = Executors.newSingleThreadExecutor(
                r -> { Thread t = new Thread(r, "djl-training"); t.setDaemon(true); return t; });

        // Per-creature adapter registry: each creature gets its own adapter ZooModel
        // loaded with trainParam=true so MemoryConsolidator can update weights in-place.
        // WorldModelEngine reads from the same ZooModel during waking (temporal separation).
        private final ConcurrentHashMap<Long, ZooModel<NDList, NDList>> perCreatureAdapters
                = new ConcurrentHashMap<>();

        Impl(ExtendedActorSystem system) {
            try {
                // Extract all model files to temp dir, then validate via ModelContract.load().
                // We extract the contract first (small, always present) to discover hasDualEncoder,
                // then extract the appropriate .pt files before hash validation runs.
                modelDir = extractContractToTemp();
                ModelContract contractForDiscovery = readContractNoValidation(modelDir);
                extractModelFiles(modelDir, contractForDiscovery);
                ModelContract contract = ModelContract.load(modelDir);
                logger.info("MLServiceExtension: model contract validated (schema_version="
                        + contract.schemaVersion + ", input_dim=" + contract.inputDim + ")");

                models = loadModels(modelDir, contract);
                logger.info("MLServiceExtension: species models loaded"
                        + (contract.hasDualEncoder ? " (dual-encoder)" : " (single-encoder)"));

                int poolSize = Math.max(1, Runtime.getRuntime().availableProcessors());
                inferenceRouter = system.actorOf(
                        new RoundRobinPool(poolSize).props(
                                Props.create(MLWorkerActor.class, () -> new MLWorkerActor(models))),
                        "mlService");
                logger.info("MLServiceExtension: inference router started with " + poolSize + " workers");

                system.registerOnTermination(() -> {
                    models.encoder().close();
                    if (models.predictor() != null)        models.predictor().close();
                    if (models.critic() != null)           models.critic().close();
                    if (models.internalEncoder() != null)  models.internalEncoder().close();
                    if (models.unifiedPredictor() != null) models.unifiedPredictor().close();
                    perCreatureAdapters.values().forEach(m -> {
                        try { m.close(); } catch (Exception ignored) {}
                    });
                    perCreatureAdapters.clear();
                    trainingExecutor.shutdownNow();
                });

            } catch (IOException | ModelNotFoundException | MalformedModelException e) {
                throw new IllegalStateException("MLServiceExtension: failed to initialise ML service", e);
            }
        }

        public ActorRef inferenceRouter() {
            return inferenceRouter;
        }

        public LoadedModels models() {
            return models;
        }

        public Path modelDir() {
            return modelDir;
        }

        public ExecutorService trainingExecutor() {
            return trainingExecutor;
        }

        /**
         * Returns the per-creature adapter ZooModel, creating it on first call.
         * Loaded with trainParam=true so MemoryConsolidator can train through it.
         * WorldModelEngine opens its own Predictor from this same ZooModel.
         */
        public ZooModel<NDList, NDList> getOrCreateAdapter(long creatureKey) {
            return perCreatureAdapters.computeIfAbsent(creatureKey, k -> {
                try {
                    return loadTrainable(modelDir, "species_adapter");
                } catch (IOException | ModelNotFoundException | MalformedModelException e) {
                    throw new IllegalStateException(
                            "Failed to load per-creature adapter for creature " + k, e);
                }
            });
        }

        /** Closes and removes the per-creature adapter. Call from CreatureActor.kill(). */
        public void releaseAdapter(long creatureKey) {
            ZooModel<NDList, NDList> model = perCreatureAdapters.remove(creatureKey);
            if (model != null) {
                try { model.close(); } catch (Exception e) {
                    logger.log(Level.WARNING,
                            "MLServiceExtension: error closing adapter for creature " + creatureKey, e);
                }
            }
        }

        public static ZooModel<NDList, NDList> loadTrainable(Path modelDir, String name)
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

        private static ModelContract readContractNoValidation(Path dir) throws IOException {
            com.fasterxml.jackson.databind.ObjectMapper mapper =
                    new com.fasterxml.jackson.databind.ObjectMapper();
            try (java.io.InputStream is = java.nio.file.Files.newInputStream(
                    dir.resolve("model_contract.json"))) {
                return mapper.readValue(is, ModelContract.class);
            }
        }

        private static Path extractContractToTemp() throws IOException {
            Path tempDir = Files.createTempDirectory("dl2l-models-");
            try (InputStream is = MLServiceExtension.class
                    .getResourceAsStream("/models/model_contract.json")) {
                if (is == null)
                    throw new IOException("Missing classpath resource: /models/model_contract.json");
                Files.copy(is, tempDir.resolve("model_contract.json"), StandardCopyOption.REPLACE_EXISTING);
            }
            return tempDir;
        }

        private static void extractModelFiles(Path tempDir, ModelContract contract) throws IOException {
            List<String> files = contract.hasUnifiedPredictor ? MODEL_FILES_UNIFIED
                               : contract.hasDualEncoder      ? MODEL_FILES_DUAL
                               :                                MODEL_FILES_SINGLE;
            for (String filename : files) {
                if (filename.equals("model_contract.json")) continue;
                try (InputStream is = MLServiceExtension.class
                        .getResourceAsStream("/models/" + filename)) {
                    if (is == null)
                        throw new IOException("Missing classpath resource: /models/" + filename);
                    Files.copy(is, tempDir.resolve(filename), StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }

        private static LoadedModels loadModels(Path modelDir, ModelContract contract)
                throws IOException, ModelNotFoundException, MalformedModelException {
            ZooModel<NDList, NDList> internalEncoder = (contract.hasDualEncoder || contract.hasUnifiedPredictor)
                    ? loadInferenceModel(modelDir, "species_internal_encoder")
                    : null;
            ZooModel<NDList, NDList> unifiedPredictor = contract.hasUnifiedPredictor
                    ? loadInferenceModel(modelDir, "species_unified_predictor")
                    : null;
            ZooModel<NDList, NDList> predictor = contract.hasUnifiedPredictor
                    ? null : loadInferenceModel(modelDir, "species_predictor");
            ZooModel<NDList, NDList> critic = contract.hasUnifiedPredictor
                    ? null : loadInferenceModel(modelDir, "species_critic");
            return new LoadedModels(
                    loadInferenceModel(modelDir, "species_encoder"),
                    predictor,
                    critic,
                    internalEncoder,
                    unifiedPredictor,
                    contract);
        }

        private static ZooModel<NDList, NDList> loadInferenceModel(Path modelDir, String modelName)
                throws IOException, ModelNotFoundException, MalformedModelException {
            Criteria<NDList, NDList> criteria = Criteria.builder()
                    .setTypes(NDList.class, NDList.class)
                    .optModelPath(modelDir)
                    .optModelName(modelName)
                    .optEngine("PyTorch")
                    .optTranslator(new NoopTranslator())
                    .build();
            return criteria.loadModel();
        }

    }
}
