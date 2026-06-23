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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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
            ZooModel<NDList, NDList> predictor,
            ZooModel<NDList, NDList> critic,
            ModelContract contract
    ) {}

    // -------------------------------------------------------------------------

    public static class Impl implements Extension {

        private static final Logger logger = Logger.getLogger(Impl.class.getName());
        private static final List<String> MODEL_FILES = List.of(
                "species_encoder.pt", "species_predictor.pt",
                "species_critic.pt", "species_adapter.pt",
                "model_contract.json");

        private final LoadedModels models;
        private final ActorRef inferenceRouter;
        private final Path modelDir;
        private final ExecutorService trainingExecutor = Executors.newSingleThreadExecutor(
                r -> { Thread t = new Thread(r, "djl-training"); t.setDaemon(true); return t; });

        Impl(ExtendedActorSystem system) {
            try {
                modelDir = extractModelsToTemp();
                ModelContract contract = ModelContract.load(modelDir);
                logger.info("MLServiceExtension: model contract validated (schema_version="
                        + contract.schemaVersion + ", input_dim=" + contract.inputDim + ")");

                models = loadModels(modelDir, contract);
                logger.info("MLServiceExtension: all four models loaded");

                int poolSize = Math.max(1, Runtime.getRuntime().availableProcessors());
                inferenceRouter = system.actorOf(
                        new RoundRobinPool(poolSize).props(
                                Props.create(MLWorkerActor.class, () -> new MLWorkerActor(models))),
                        "mlService");
                logger.info("MLServiceExtension: inference router started with " + poolSize + " workers");

                system.registerOnTermination(() -> {
                    models.encoder().close();
                    models.predictor().close();
                    models.critic().close();
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

        private static Path extractModelsToTemp() throws IOException {
            Path tempDir = Files.createTempDirectory("dl2l-models-");
            for (String filename : MODEL_FILES) {
                try (InputStream is = MLServiceExtension.class
                        .getResourceAsStream("/models/" + filename)) {
                    if (is == null)
                        throw new IOException("Missing classpath resource: /models/" + filename);
                    Files.copy(is, tempDir.resolve(filename), StandardCopyOption.REPLACE_EXISTING);
                }
            }
            return tempDir;
        }

        private static LoadedModels loadModels(Path modelDir, ModelContract contract)
                throws IOException, ModelNotFoundException, MalformedModelException {
            return new LoadedModels(
                    loadInferenceModel(modelDir, "species_encoder"),
                    loadInferenceModel(modelDir, "species_predictor"),
                    loadInferenceModel(modelDir, "species_critic"),
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
