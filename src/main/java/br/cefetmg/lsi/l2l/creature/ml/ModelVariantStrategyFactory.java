package br.cefetmg.lsi.l2l.creature.ml;

/**
 * Factory that creates the correct {@link ModelVariantStrategy} from a loaded
 * {@link ModelContract}.
 *
 * The variant is read from model_contract.json's {@code model_variant} field.
 * Unknown variants fall back to {@link SingleEncoderStrategy} with a logged warning.
 */
public class ModelVariantStrategyFactory {

    private ModelVariantStrategyFactory() {}

    public static ModelVariantStrategy forContract(ModelContract contract) {
        // Legacy compat: contracts exported before model_variant was introduced default the field
        // to "single", but if hasDualEncoder=true the predictor actually expects
        // concat(z_world, z_internal) input — i.e. the old DualSpeciesModel layout.
        if ("single".equals(contract.modelVariant) && contract.hasDualEncoder) {
            java.util.logging.Logger.getLogger(ModelVariantStrategyFactory.class.getName())
                    .warning("model_contract.json has has_internal_encoder=true but no model_variant field "
                             + "— treating as 'dual' for backwards compatibility");
            return new DualPredictorStrategy();
        }
        return switch (contract.modelVariant) {
            case "dual"                -> new DualPredictorStrategy();
            case "internal_critic"     -> new InternalCriticStrategy();
            case "internal_predictor"  -> new InternalPredictorStrategy();
            default -> {
                if (!"single".equals(contract.modelVariant)) {
                    java.util.logging.Logger.getLogger(ModelVariantStrategyFactory.class.getName())
                            .warning("Unknown model_variant '" + contract.modelVariant
                                     + "' — falling back to SingleEncoderStrategy");
                }
                yield new SingleEncoderStrategy();
            }
        };
    }
}
