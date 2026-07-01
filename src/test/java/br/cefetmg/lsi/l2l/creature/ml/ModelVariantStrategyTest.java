package br.cefetmg.lsi.l2l.creature.ml;

import ai.djl.ndarray.NDArray;
import ai.djl.ndarray.NDManager;
import ai.djl.ndarray.types.Shape;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the three ModelVariantStrategy implementations.
 *
 * Verifies tensor routing (shapes, concatenation) without loading any .pt files.
 * Uses DJL NDManager in CPU mode to create test tensors.
 */
class ModelVariantStrategyTest {

    private static final int BATCH       = 4;
    private static final int LATENT_DIM  = 64;
    private static final int INTERNAL_DIM = 16;

    // ── SingleEncoderStrategy ────────────────────────────────────────────────

    @Test
    void single_doesNotRequireInternalState() {
        assertFalse(new SingleEncoderStrategy().requiresInternalState());
    }

    @Test
    void single_predictorInputIsAdaptedZOnly() {
        try (NDManager mgr = NDManager.newBaseManager()) {
            NDArray adaptedZ  = mgr.ones(new Shape(BATCH, LATENT_DIM));
            NDArray zInternal = mgr.ones(new Shape(BATCH, INTERNAL_DIM));

            NDArray predIn = new SingleEncoderStrategy().buildPredictorInput(adaptedZ, zInternal);

            assertArrayEquals(new long[]{BATCH, LATENT_DIM}, predIn.getShape().getShape(),
                    "SingleEncoder predictor input must be adaptedZ only");
            assertTrue(predIn == adaptedZ, "Must return same NDArray instance");
        }
    }

    @Test
    void single_criticInputIsZNextOnly() {
        try (NDManager mgr = NDManager.newBaseManager()) {
            NDArray zNext     = mgr.ones(new Shape(BATCH, LATENT_DIM));
            NDArray zInternal = mgr.ones(new Shape(BATCH, INTERNAL_DIM));

            NDArray criticIn = new SingleEncoderStrategy().buildCriticInput(zNext, zInternal);

            assertArrayEquals(new long[]{BATCH, LATENT_DIM}, criticIn.getShape().getShape(),
                    "SingleEncoder critic input must be zNext only");
            assertTrue(criticIn == zNext, "Must return same NDArray instance");
        }
    }

    @Test
    void single_toleratesNullInternalState() {
        try (NDManager mgr = NDManager.newBaseManager()) {
            NDArray adaptedZ = mgr.ones(new Shape(BATCH, LATENT_DIM));
            NDArray zNext    = mgr.ones(new Shape(BATCH, LATENT_DIM));

            assertDoesNotThrow(() -> new SingleEncoderStrategy().buildPredictorInput(adaptedZ, null));
            assertDoesNotThrow(() -> new SingleEncoderStrategy().buildCriticInput(zNext, null));
        }
    }

    // ── DualPredictorStrategy ────────────────────────────────────────────────

    @Test
    void dual_requiresInternalState() {
        assertTrue(new DualPredictorStrategy().requiresInternalState());
    }

    @Test
    void dual_predictorInputIsConcatenated() {
        try (NDManager mgr = NDManager.newBaseManager()) {
            NDArray adaptedZ  = mgr.ones(new Shape(BATCH, LATENT_DIM));
            NDArray zInternal = mgr.ones(new Shape(BATCH, INTERNAL_DIM));

            NDArray predIn = new DualPredictorStrategy().buildPredictorInput(adaptedZ, zInternal);

            assertArrayEquals(new long[]{BATCH, LATENT_DIM + INTERNAL_DIM}, predIn.getShape().getShape(),
                    "DualPredictor predictor input must be concat(adaptedZ, zInternal)");
        }
    }

    @Test
    void dual_criticInputIsConcatenated() {
        try (NDManager mgr = NDManager.newBaseManager()) {
            NDArray zNext     = mgr.ones(new Shape(BATCH, LATENT_DIM));
            NDArray zInternal = mgr.ones(new Shape(BATCH, INTERNAL_DIM));

            NDArray criticIn = new DualPredictorStrategy().buildCriticInput(zNext, zInternal);

            assertArrayEquals(new long[]{BATCH, LATENT_DIM + INTERNAL_DIM}, criticIn.getShape().getShape(),
                    "DualPredictor critic input must be concat(zNext, zInternal)");
        }
    }

    // ── InternalCriticStrategy ───────────────────────────────────────────────

    @Test
    void internalCritic_requiresInternalState() {
        assertTrue(new InternalCriticStrategy().requiresInternalState());
    }

    @Test
    void internalCritic_predictorInputIsAdaptedZOnly() {
        try (NDManager mgr = NDManager.newBaseManager()) {
            NDArray adaptedZ  = mgr.ones(new Shape(BATCH, LATENT_DIM));
            NDArray zInternal = mgr.ones(new Shape(BATCH, INTERNAL_DIM));

            NDArray predIn = new InternalCriticStrategy().buildPredictorInput(adaptedZ, zInternal);

            assertArrayEquals(new long[]{BATCH, LATENT_DIM}, predIn.getShape().getShape(),
                    "InternalCritic predictor must be blind to internal state");
            assertTrue(predIn == adaptedZ, "Must return same NDArray instance");
        }
    }

    @Test
    void internalCritic_criticInputIsConcatenated() {
        try (NDManager mgr = NDManager.newBaseManager()) {
            NDArray zNext     = mgr.ones(new Shape(BATCH, LATENT_DIM));
            NDArray zInternal = mgr.ones(new Shape(BATCH, INTERNAL_DIM));

            NDArray criticIn = new InternalCriticStrategy().buildCriticInput(zNext, zInternal);

            assertArrayEquals(new long[]{BATCH, LATENT_DIM + INTERNAL_DIM}, criticIn.getShape().getShape(),
                    "InternalCritic critic input must be concat(zNext, zInternal)");
        }
    }

    // ── InternalPredictorStrategy ────────────────────────────────────────────

    @Test
    void internalPredictor_requiresInternalState() {
        assertTrue(new InternalPredictorStrategy().requiresInternalState());
    }

    @Test
    void internalPredictor_predictorInputIsConcatenated() {
        try (NDManager mgr = NDManager.newBaseManager()) {
            NDArray adaptedZ  = mgr.ones(new Shape(BATCH, LATENT_DIM));
            NDArray zInternal = mgr.ones(new Shape(BATCH, INTERNAL_DIM));

            NDArray predIn = new InternalPredictorStrategy().buildPredictorInput(adaptedZ, zInternal);

            assertArrayEquals(new long[]{BATCH, LATENT_DIM + INTERNAL_DIM}, predIn.getShape().getShape(),
                    "InternalPredictor predictor input must be concat(adaptedZ, zInternal)");
        }
    }

    @Test
    void internalPredictor_criticInputIsZNextOnly() {
        try (NDManager mgr = NDManager.newBaseManager()) {
            NDArray zNext     = mgr.ones(new Shape(BATCH, LATENT_DIM));
            NDArray zInternal = mgr.ones(new Shape(BATCH, INTERNAL_DIM));

            NDArray criticIn = new InternalPredictorStrategy().buildCriticInput(zNext, zInternal);

            assertArrayEquals(new long[]{BATCH, LATENT_DIM}, criticIn.getShape().getShape(),
                    "InternalPredictor critic must be blind to internal state");
            assertTrue(criticIn == zNext, "Must return same NDArray instance");
        }
    }

    // ── ModelVariantStrategyFactory ──────────────────────────────────────────

    @Test
    void factory_createsCorrectStrategyForEachVariant() {
        ModelContract single  = contractWithVariant("single");
        ModelContract dual    = contractWithVariant("dual");
        ModelContract ic      = contractWithVariant("internal_critic");
        ModelContract ip      = contractWithVariant("internal_predictor");
        ModelContract unknown = contractWithVariant("future_variant");

        assertInstanceOf(SingleEncoderStrategy.class,    ModelVariantStrategyFactory.forContract(single));
        assertInstanceOf(DualPredictorStrategy.class,    ModelVariantStrategyFactory.forContract(dual));
        assertInstanceOf(InternalCriticStrategy.class,   ModelVariantStrategyFactory.forContract(ic));
        assertInstanceOf(InternalPredictorStrategy.class,ModelVariantStrategyFactory.forContract(ip));
        assertInstanceOf(SingleEncoderStrategy.class,    ModelVariantStrategyFactory.forContract(unknown),
                "Unknown variant must fall back to single-encoder strategy");
    }

    @Test
    void factory_legacyDualEncoderContractTreatedAsDual() {
        // Old contracts: has_internal_encoder=true but no model_variant field (defaults to "single").
        // Must be treated as "dual" (the only dual variant before internal_critic was introduced).
        ModelContract legacy = contractWithVariant("single");
        legacy.hasDualEncoder = true;

        assertInstanceOf(DualPredictorStrategy.class, ModelVariantStrategyFactory.forContract(legacy),
                "Legacy dual-encoder contract must map to DualPredictorStrategy");
    }

    @Test
    void factory_variantNamesMatchContract() {
        assertEquals("single",              ModelVariantStrategyFactory.forContract(contractWithVariant("single")).variantName());
        assertEquals("dual",                ModelVariantStrategyFactory.forContract(contractWithVariant("dual")).variantName());
        assertEquals("internal_critic",     ModelVariantStrategyFactory.forContract(contractWithVariant("internal_critic")).variantName());
        assertEquals("internal_predictor",  ModelVariantStrategyFactory.forContract(contractWithVariant("internal_predictor")).variantName());
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private static ModelContract contractWithVariant(String variant) {
        ModelContract c = new ModelContract();
        c.modelVariant = variant;
        return c;
    }
}
