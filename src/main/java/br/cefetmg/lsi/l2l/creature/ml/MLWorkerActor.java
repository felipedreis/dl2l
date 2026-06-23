package br.cefetmg.lsi.l2l.creature.ml;

import ai.djl.inference.Predictor;
import ai.djl.ndarray.NDArray;
import ai.djl.ndarray.NDList;
import ai.djl.ndarray.NDManager;
import ai.djl.translate.TranslateException;
import akka.actor.UntypedActor;

import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Pool member actor for ML inference.
 * Each instance owns its own Predictor objects (DJL Predictors are not thread-safe;
 * single-actor-per-predictor is the safe pattern in an Akka pool).
 *
 * Phase 5: standard path — encode perception → predictor → critic.
 * Epic 6: WorldModelFilter applies the per-creature adapter and sends encodedLatent
 *         directly, bypassing the encoder here.
 */
public class MLWorkerActor extends UntypedActor {

    private static final Logger logger = Logger.getLogger(MLWorkerActor.class.getName());

    private final MLServiceExtension.LoadedModels models;

    private Predictor<NDList, NDList> encoderPredictor;
    private Predictor<NDList, NDList> predictorPredictor;
    private Predictor<NDList, NDList> criticPredictor;

    public MLWorkerActor(MLServiceExtension.LoadedModels models) {
        this.models = models;
    }

    @Override
    public void preStart() {
        encoderPredictor   = models.encoder().newPredictor();
        predictorPredictor = models.predictor().newPredictor();
        criticPredictor    = models.critic().newPredictor();
    }

    @Override
    public void postStop() {
        encoderPredictor.close();
        predictorPredictor.close();
        criticPredictor.close();
    }

    @Override
    public void onReceive(Object message) {
        if (message instanceof InferenceRequest req) {
            float[] result = runInference(req);
            req.replyTo().tell(new InferenceResponse(result), self());
        } else {
            unhandled(message);
        }
    }

    private float[] runInference(InferenceRequest req) {
        try (NDManager mgr = NDManager.newBaseManager()) {
            NDArray latent;
            if (req.encodedLatent() != null) {
                // Epic 6 path: WorldModelFilter has already applied the per-creature adapter.
                latent = mgr.create(req.encodedLatent());
            } else {
                // Phase 5 path: encode perception → latent.
                NDArray percInput = mgr.create(req.perceptionFeatures());
                latent = encoderPredictor.predict(new NDList(percInput)).singletonOrThrow();
            }

            NDArray action = mgr.create(req.actionOneHot());
            NDArray nextLatent = predictorPredictor.predict(new NDList(latent, action)).singletonOrThrow();
            return criticPredictor.predict(new NDList(nextLatent, action)).singletonOrThrow().toFloatArray();

        } catch (TranslateException e) {
            logger.log(Level.WARNING, "Inference failed, returning zero delta", e);
            return new float[models.contract().emotionDim];
        }
    }
}
