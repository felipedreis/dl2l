package br.cefetmg.lsi.l2l.creature.ml;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public class ModelContract {

    private static final int SUPPORTED_SCHEMA_VERSION = 1;
    private static final List<String> MODEL_FILES =
            List.of("species_adapter.pt", "species_critic.pt", "species_encoder.pt", "species_predictor.pt");

    @JsonProperty("schema_version") public int schemaVersion;
    @JsonProperty("input_dim")      public int inputDim;
    @JsonProperty("latent_dim")     public int latentDim;
    @JsonProperty("action_dim")     public int actionDim;
    @JsonProperty("emotion_dim")    public int emotionDim;
    @JsonProperty("model_hash")     public String modelHash;

    public static ModelContract load(Path modelDir) throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        ModelContract contract;
        try (InputStream is = Files.newInputStream(modelDir.resolve("model_contract.json"))) {
            contract = mapper.readValue(is, ModelContract.class);
        }
        contract.validateSchemaVersion();
        contract.validateHash(modelDir);
        return contract;
    }

    private void validateSchemaVersion() {
        if (schemaVersion != SUPPORTED_SCHEMA_VERSION) {
            throw new IllegalStateException(
                    "model_contract.json schema_version=" + schemaVersion
                    + " not supported (expected " + SUPPORTED_SCHEMA_VERSION + ")");
        }
    }

    private void validateHash(Path modelDir) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }

        // Alphabetical order matches the hash computed at training time.
        for (String filename : MODEL_FILES) {
            try (DigestInputStream dis = new DigestInputStream(
                    Files.newInputStream(modelDir.resolve(filename)), digest)) {
                dis.transferTo(OutputStream.nullOutputStream());
            }
        }

        String computed = HexFormat.of().formatHex(digest.digest());
        if (!computed.equals(modelHash)) {
            throw new IllegalStateException(
                    "Model hash mismatch — contract expects " + modelHash
                    + " but computed " + computed
                    + ". Re-export the model or update model_contract.json.");
        }
    }
}
