package br.cefetmg.lsi.l2l.creature.ml;

import ai.djl.engine.Engine;
import ai.djl.ndarray.NDArray;
import ai.djl.ndarray.NDArrays;
import ai.djl.ndarray.NDList;
import ai.djl.ndarray.NDManager;
import ai.djl.ndarray.index.NDIndex;
import ai.djl.ndarray.types.Shape;
import ai.djl.repository.zoo.ZooModel;
import ai.djl.training.DefaultTrainingConfig;
import ai.djl.training.GradientCollector;
import ai.djl.training.Trainer;
import ai.djl.training.loss.Loss;
import ai.djl.training.optimizer.Adam;
import ai.djl.training.tracker.Tracker;
import akka.actor.ActorRef;
import akka.actor.TypedActor;
import akka.actor.TypedProps;
import akka.actor.UntypedActor;
import br.cefetmg.lsi.l2l.common.Constants;
import br.cefetmg.lsi.l2l.creature.Creature;
import br.cefetmg.lsi.l2l.creature.CreatureActor;
import br.cefetmg.lsi.l2l.creature.bd.ConsolidationBatchStat;
import br.cefetmg.lsi.l2l.creature.bd.ConsolidationEpisodeStat;
import br.cefetmg.lsi.l2l.creature.common.ActionType;
import br.cefetmg.lsi.l2l.creature.memory.Engram;
import br.cefetmg.lsi.l2l.creature.memory.MemorySystem;
import br.cefetmg.lsi.l2l.world.FruitType;
import br.cefetmg.lsi.l2l.world.PlantType;

import javax.persistence.EntityManager;
import javax.persistence.Persistence;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Per-creature actor that consolidates recent engrams into the individual adapter
 * during sleep. Triggered by SleepStarted; aborted cooperatively by WakeUp.
 *
 * Each MemoryConsolidator loads its own copies of the four JEPA models with
 * trainParam=true so gradient can flow through the full prediction-error chain:
 *   encoder → adapter → predictor → critic → MSE(pred_delta, actual_delta)
 * Only the adapter parameters are updated via step().
 *
 * Training is submitted to MLServiceExtension.trainingExecutor() — a JVM-global
 * single-threaded executor — which serialises all creatures' backward passes and
 * prevents concurrent GradientCollector use (a DJL/PyTorch constraint).
 */
public class MemoryConsolidator extends UntypedActor {

    private static final Logger logger = Logger.getLogger(MemoryConsolidator.class.getName());

    // Alphabetical order matches model_contract.json action_index_order
    private static final ActionType[] ACTION_ORDER = {
            ActionType.APPROACH, ActionType.AVOID, ActionType.EAT, ActionType.ESCAPE,
            ActionType.PLAY, ActionType.SLEEP, ActionType.TOUCH, ActionType.TURN, ActionType.WANDER
    };

    private final long creatureKey;
    private MemorySystem memory;
    private MLServiceExtension.Impl mlExt;
    private ModelContract contract;
    private ModelVariantStrategy strategy;

    // Per-creature models loaded with trainParam=true for gradient flow through the full chain.
    // Frozen models accumulate gradients but are never stepped — their weights stay fixed.
    private ZooModel<NDList, NDList> encoderModel;
    private ZooModel<NDList, NDList> adapterModel;
    private ZooModel<NDList, NDList> predictorModel;          // null when unified
    private ZooModel<NDList, NDList> criticModel;             // null when unified
    private ZooModel<NDList, NDList> internalEncoderModel;    // null when single-encoder
    private ZooModel<NDList, NDList> unifiedPredictorModel;   // null when not unified

    private Trainer encoderTrainer;
    private Trainer adapterTrainer;
    private Trainer predictorTrainer;         // null when unified
    private Trainer criticTrainer;            // null when unified
    private Trainer internalEncoderTrainer;   // null when single-encoder
    private Trainer unifiedPredictorTrainer;  // null when not unified

    private final AtomicBoolean abortFlag = new AtomicBoolean(false);
    private CompletableFuture<?> consolidationTask;

    private final EntityManager em = Persistence.createEntityManagerFactory("L2LPU",
            br.cefetmg.lsi.l2l.creature.bd.JpaPersister.jdbcUrlOverride()).createEntityManager();

    public MemoryConsolidator(long creatureKey) {
        this.creatureKey = creatureKey;
    }

    @Override
    public void preStart() throws Exception {
        super.preStart();

        Creature creature = TypedActor.get(context().system())
                .typedActorOf(new TypedProps<>(Creature.class, CreatureActor.class), context().parent());
        memory = creature.memory();

        mlExt = MLServiceExtension.of(context().system());
        contract = ModelContract.load(mlExt.modelDir());

        DefaultTrainingConfig config = new DefaultTrainingConfig(Loss.l2Loss())
                .optOptimizer(Adam.builder()
                        .optLearningRateTracker(Tracker.fixed(0.001f))
                        .build());

        encoderModel = MLServiceExtension.Impl.loadTrainable(mlExt.modelDir(), "species_encoder");
        adapterModel = mlExt.getOrCreateAdapter(creatureKey);

        encoderTrainer = encoderModel.newTrainer(config);
        adapterTrainer = adapterModel.newTrainer(config);

        if (contract.hasUnifiedPredictor) {
            unifiedPredictorModel   = MLServiceExtension.Impl.loadTrainable(
                    mlExt.modelDir(), "species_unified_predictor");
            unifiedPredictorTrainer = unifiedPredictorModel.newTrainer(config);
            internalEncoderModel    = MLServiceExtension.Impl.loadTrainable(
                    mlExt.modelDir(), "species_internal_encoder");
            internalEncoderTrainer  = internalEncoderModel.newTrainer(config);
        } else {
            predictorModel = MLServiceExtension.Impl.loadTrainable(mlExt.modelDir(), "species_predictor");
            criticModel    = MLServiceExtension.Impl.loadTrainable(mlExt.modelDir(), "species_critic");
            predictorTrainer = predictorModel.newTrainer(config);
            criticTrainer    = criticModel.newTrainer(config);
            if (contract.hasDualEncoder) {
                internalEncoderModel   = MLServiceExtension.Impl.loadTrainable(
                        mlExt.modelDir(), "species_internal_encoder");
                internalEncoderTrainer = internalEncoderModel.newTrainer(config);
            }
        }

        strategy = ModelVariantStrategyFactory.forContract(contract);

        logger.info("MemoryConsolidator[" + creatureKey + "]: per-creature models loaded"
                + " (" + contract.modelVariant + ")");
    }

    @Override
    public void postStop() throws Exception {
        super.postStop();
        // Signal any in-progress training task to stop between batches before
        // we close the native model handles. Without this, a mid-flight batch
        // throws "PyTorch model handle has been released" on every pending task.
        abortFlag.set(true);
        closeSilently(encoderTrainer);
        closeSilently(adapterTrainer);
        closeSilently(predictorTrainer);
        closeSilently(criticTrainer);
        closeSilently(internalEncoderTrainer);
        closeSilently(unifiedPredictorTrainer);
        closeSilently(encoderModel);
        // adapterModel is owned by MLServiceExtension; released via releaseAdapter() in CreatureActor.kill()
        closeSilently(predictorModel);
        closeSilently(criticModel);
        closeSilently(internalEncoderModel);
        closeSilently(unifiedPredictorModel);
        em.close();
        logger.info("MemoryConsolidator[" + creatureKey + "]: resources released");
    }

    private void closeSilently(AutoCloseable c) {
        if (c != null) try { c.close(); } catch (Exception e) {
            logger.log(Level.WARNING, "MemoryConsolidator[" + creatureKey + "]: error closing resource", e);
        }
    }

    @Override
    public void onReceive(Object message) {
        if (message instanceof SleepStarted) {
            handleSleepStarted((SleepStarted) message);
        } else if (message instanceof WakeUp) {
            handleWakeUp();
        } else if (message instanceof ConsolidationResult) {
            persistResult((ConsolidationResult) message);
        } else {
            unhandled(message);
        }
    }

    private void handleSleepStarted(SleepStarted msg) {
        abortFlag.set(true); // abort any prior consolidation task still running

        long onsetCycle = msg.onsetCycle();
        List<Engram> engrams = memory.getRecentEngrams(Constants.CONSOLIDATION_WINDOW);
        if (engrams.isEmpty()) {
            logger.fine("MemoryConsolidator[" + creatureKey + "]: sleep started but no engrams, skipping");
            // Persist a zero-batch skipped episode so analysis can detect skip events.
            ConsolidationEpisodeStat skipped = new ConsolidationEpisodeStat(
                    creatureKey, onsetCycle, 0, 0.0, 0.0, 0, true);
            persistResult(new ConsolidationResult(List.of(), skipped));
            return;
        }

        double meanElig = engrams.stream().mapToDouble(Engram::eligibility).average().orElse(0.0);
        double stdElig  = Math.sqrt(engrams.stream()
                .mapToDouble(e -> Math.pow(e.eligibility() - meanElig, 2)).average().orElse(0.0));

        abortFlag.set(false);
        ActorRef me = self();

        logger.fine(String.format(
                "MemoryConsolidator[%d]: consolidating %d engrams (mean_elig=%.3f std_elig=%.3f)",
                creatureKey, engrams.size(), meanElig, stdElig));

        consolidationTask = CompletableFuture
                .supplyAsync(() -> trainAdapter(engrams, abortFlag, onsetCycle, meanElig, stdElig),
                        mlExt.trainingExecutor())
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        logger.log(Level.WARNING,
                                "MemoryConsolidator[" + creatureKey + "]: consolidation failed", ex);
                    } else {
                        logger.fine(String.format(
                                "MemoryConsolidator[%d]: consolidation %s, batches=%d",
                                creatureKey,
                                result.episode().isAborted() ? "aborted" : "complete",
                                result.episode().getBatchesCompleted()));
                        me.tell(result, ActorRef.noSender());
                    }
                });
    }

    private void handleWakeUp() {
        abortFlag.set(true);
        logger.fine("MemoryConsolidator[" + creatureKey + "]: wake-up signalled, aborting after current batch");
    }

    private void persistResult(ConsolidationResult result) {
        em.getTransaction().begin();
        em.persist(result.episode());
        result.batches().forEach(em::persist);
        em.getTransaction().commit();
        em.clear();
    }

    // -----------------------------------------------------------------------
    // Training loop — runs on mlExt.trainingExecutor() (single-threaded).
    // The actor thread (wm-dispatcher) stays free to receive WakeUp.
    // -----------------------------------------------------------------------

    private ConsolidationResult trainAdapter(List<Engram> engrams, AtomicBoolean abort,
                                             long onsetCycle, double meanElig, double stdElig) {
        List<ConsolidationBatchStat> batchStats = new ArrayList<>();
        int batchIndex = 0;

        try {
            int start = 0;
            while (start < engrams.size()) {
                if (abort.get()) break;

                int end = Math.min(start + Constants.CONSOLIDATION_BATCH_SIZE, engrams.size());
                List<Engram> batch = engrams.subList(start, end);

                float loss;
                try (NDManager batchMgr = NDManager.newBaseManager()) {
                    loss = trainBatch(batch, batchMgr);
                } catch (IllegalStateException e) {
                    // Native model handle was released (creature died while training).
                    // Abort cleanly; remaining queued tasks will see abortFlag=true.
                    logger.info("MemoryConsolidator[" + creatureKey + "]: model released mid-batch, stopping");
                    break;
                }
                // Only the adapter parameters are updated; frozen models accumulate grads but
                // are never stepped, so their weights remain unchanged.
                adapterTrainer.step();

                batchStats.add(new ConsolidationBatchStat(
                        creatureKey, onsetCycle, batchIndex, batch.size(), loss));
                batchIndex++;
                start = end;
            }
        } catch (Exception e) {
            logger.log(Level.WARNING,
                    "MemoryConsolidator[" + creatureKey + "]: error during adapter training", e);
        }

        boolean aborted = abort.get();
        ConsolidationEpisodeStat episode = new ConsolidationEpisodeStat(
                creatureKey, onsetCycle, engrams.size(), meanElig, stdElig, batchIndex, aborted);

        return new ConsolidationResult(batchStats, episode);
    }

    // Full prediction-error chain.
    // Single-encoder:   encoder → adapter → predictor(action) → critic → pred_delta
    // Dual-encoder:     encoder → adapter → concat(adapted_z, z_internal) → predictor → critic
    // Unified:          encoder → adapter → unifiedPredictor(adapted_z, action, z_internal)
    //                              → (z_next, emotion_pred)
    // loss = MSE(pred_delta, actual_delta) * mean(eligibility)
    private float trainBatch(List<Engram> batch, NDManager mgr) {
        int n = batch.size();
        boolean useUnified = contract.hasUnifiedPredictor;
        boolean needInternal = useUnified || contract.hasDualEncoder;

        float[] percData     = new float[n * contract.inputDim];
        float[] actionData   = new float[n * contract.actionDim];
        float[] targetData   = new float[n * contract.emotionDim];
        float[] weights      = new float[n];
        float[] internalData = needInternal ? new float[n * contract.internalStateDim] : null;

        for (int i = 0; i < n; i++) {
            Engram e = batch.get(i);
            System.arraycopy(encodePerception(e), 0, percData,   i * contract.inputDim,  contract.inputDim);
            System.arraycopy(encodeAction(e.actionType()), 0, actionData, i * contract.actionDim, contract.actionDim);
            float delta = (float) Math.tanh(e.emotionDelta());
            Arrays.fill(targetData, i * contract.emotionDim, (i + 1) * contract.emotionDim, delta);
            weights[i] = (float) e.eligibility();
            if (internalData != null) {
                float[] ht = encodeInternalState(e);
                System.arraycopy(ht, 0, internalData, i * contract.internalStateDim, contract.internalStateDim);
            }
        }

        NDArray percInput   = mgr.create(percData,   new Shape(n, contract.inputDim));
        NDArray actionBatch = mgr.create(actionData, new Shape(n, contract.actionDim));
        NDArray target      = mgr.create(targetData, new Shape(n, contract.emotionDim));
        NDArray weightArr   = mgr.create(weights,    new Shape(n));

        zeroGradients(encoderTrainer);
        zeroGradients(adapterTrainer);
        if (predictorTrainer != null)        zeroGradients(predictorTrainer);
        if (criticTrainer != null)           zeroGradients(criticTrainer);
        if (internalEncoderTrainer != null)  zeroGradients(internalEncoderTrainer);
        if (unifiedPredictorTrainer != null) zeroGradients(unifiedPredictorTrainer);

        float lossValue;
        try (GradientCollector gc = Engine.getInstance().newGradientCollector()) {
            NDArray z        = encoderTrainer.forward(new NDList(percInput)).singletonOrThrow();
            NDArray adaptedZ = adapterTrainer.forward(new NDList(z)).singletonOrThrow();

            NDArray predDelta;
            if (useUnified && internalEncoderTrainer != null && internalData != null) {
                NDArray zInternal = internalEncoderTrainer.forward(
                        new NDList(mgr.create(internalData, new Shape(n, contract.internalStateDim))))
                        .singletonOrThrow();
                // unified predictor returns NDList([z_next, emotion]); use emotion (index 1)
                NDList result = unifiedPredictorTrainer.forward(
                        new NDList(adaptedZ, actionBatch, zInternal));
                predDelta = result.get(1);
            } else {
                NDArray zInternal = (internalEncoderTrainer != null && internalData != null)
                        ? internalEncoderTrainer.forward(
                                new NDList(mgr.create(internalData, new Shape(n, contract.internalStateDim))))
                                .singletonOrThrow()
                        : null;
                NDArray predictorInput = strategy.buildPredictorInput(adaptedZ, zInternal);
                NDArray nextZ          = predictorTrainer.forward(new NDList(predictorInput, actionBatch)).singletonOrThrow();
                NDArray criticInput    = strategy.buildCriticInput(nextZ, zInternal);
                predDelta              = criticTrainer.forward(new NDList(criticInput, actionBatch)).singletonOrThrow();
            }

            NDArray rawLoss      = adapterTrainer.getLoss().evaluate(new NDList(target), new NDList(predDelta));
            NDArray weightedLoss = rawLoss.mul(weightArr.mean());
            lossValue = weightedLoss.getFloat();
            gc.backward(weightedLoss);
        }
        return lossValue;
    }

    // -----------------------------------------------------------------------
    // Feature encoders — must match model_contract.json feature order exactly
    // -----------------------------------------------------------------------

    private float[] encodePerception(Engram e) {
        float[] f = new float[contract.inputDim];
        f[0] = (float) e.perception().distance;
        f[1] = (float) e.perception().angle;
        f[2] = (float) Math.sin(e.perception().angle);
        if (e.perception().objectType.isDefined()) {
            Object type = e.perception().objectType.get();
            String typeName = "type_" + (type instanceof FruitType ft ? ft.name()
                                       : type instanceof PlantType pt ? pt.name() : "");
            int idx = contract.perceptionFeatureOrder.indexOf(typeName);
            if (idx >= 0 && idx < f.length) f[idx] = 1f;
        }
        return f;
    }

    // Encodes the creature's homeostatic state at decision time (from Engram.emotionAtDecision)
    // into the live emotion dims expected by the InternalEncoder.
    private float[] encodeInternalState(Engram e) {
        float[] f = new float[contract.internalStateDim];
        if (e.emotionAtDecision() == null) return f;
        // liveEmotionDims drives the order to match InternalEncoder's expected input.
        for (int i = 0; i < contract.liveEmotionDims.size() && i < f.length; i++) {
            // emotionAtDecision carries the level of the *max* emotion at decision time,
            // but not the full vector. Use its level only if names match; zero otherwise.
            // A richer per-emotion snapshot would require extending the Engram record.
            String dimName = contract.emotionIndexOrder.get(contract.liveEmotionDims.get(i));
            if (dimName.equals(e.emotionAtDecision().getName())) {
                f[i] = (float) e.emotionAtDecision().getLevel();
            }
        }
        return f;
    }

    private float[] encodeAction(ActionType action) {
        float[] hot = new float[contract.actionDim];
        for (int i = 0; i < ACTION_ORDER.length; i++) {
            if (ACTION_ORDER[i] == action) {
                hot[i] = 1f;
                break;
            }
        }
        return hot;
    }

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


    private record ConsolidationResult(List<ConsolidationBatchStat> batches, ConsolidationEpisodeStat episode) {}
}
