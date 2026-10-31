package br.cefetmg.lsi.l2l.cluster.settings;

import br.cefetmg.lsi.l2l.common.Point;
import br.cefetmg.lsi.l2l.common.Seeds;
import br.cefetmg.lsi.l2l.creature.bd.ActionSelectionType;
import br.cefetmg.lsi.l2l.creature.conditioning.expectancy.ExpectancyMode;
import br.cefetmg.lsi.l2l.world.FruitType;
import br.cefetmg.lsi.l2l.world.PlantType;
import br.cefetmg.lsi.l2l.world.PositionFactory;
import br.cefetmg.lsi.l2l.world.WorldObjectType;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Created by felipe on 03/04/17.
 */
public class Simulation {

    private Long numHolders;
    private PositionFactory positionFactory;

    private List<CreatureSetting> creatureSettings;
    private List<WorldObjectSetting> worldObjectSettings;

    private Point worldBoundaries;

    private boolean reposition;

    private int repositionDelaySeconds;

    private int maxRuntimeMinutes;

    private LearningSettings learningSettings;

    private long seed;

    private boolean seedGenerated;

    public Simulation(){}

    public Simulation(Config config) {
        Config fullConfig = config.withFallback(ConfigFactory.load("simulation"));

        Config worldSize = fullConfig.getConfig("simulation.worldSize");

        worldBoundaries = new Point(worldSize.getDouble("width"), worldSize.getDouble("height"));

        numHolders = fullConfig.getLong("simulation.holders");

        seedGenerated = !fullConfig.hasPath("simulation.seed");
        seed = seedGenerated ? Seeds.fresh() : fullConfig.getLong("simulation.seed");

        try {
            Class<?> factoryClass = getClass().getClassLoader()
                    .loadClass(fullConfig.getString("simulation.positionFactory"));
            positionFactory = (PositionFactory) factoryClass.getConstructor(Point.class, long.class)
                    .newInstance(worldBoundaries, Seeds.derive(seed, "world"));

        } catch (Exception ex) {
            throw new IllegalArgumentException("Could\'t load the PositionFactory");
        }

        creatureSettings = new ArrayList<>();

        for(Config creatureConfig : fullConfig.getConfigList("simulation.creatureSettings")) {
            CreatureSetting creatureSetting = new CreatureSetting();
            creatureSetting.setQuantity(creatureConfig.getInt("quantity"));
            creatureSettings.add(creatureSetting);
        }

        worldObjectSettings = new ArrayList<>();

        for(Config worldObjectConfig : fullConfig.getConfigList("simulation.worldObjectSettings")) {
            WorldObjectSetting worldObjectSetting = new WorldObjectSetting();
            worldObjectSetting.setQuantity(worldObjectConfig.getInt("quantity"));
            worldObjectSetting.setType(parseWorldObjectType(worldObjectConfig.getString("objectType")));
            worldObjectSettings.add(worldObjectSetting);
        }

        reposition = fullConfig.getBoolean("simulation.reposition");

        repositionDelaySeconds = fullConfig.hasPath("simulation.repositionDelaySeconds")
                ? fullConfig.getInt("simulation.repositionDelaySeconds") : 0;

        maxRuntimeMinutes = fullConfig.hasPath("simulation.maxRuntimeMinutes")
                ? fullConfig.getInt("simulation.maxRuntimeMinutes") : 0;

        learningSettings = parseLearningSettings(fullConfig);
    }

    private static LearningSettings parseLearningSettings(Config fullConfig) {
        if (!fullConfig.hasPath("simulation.learningSettings")) {
            return new LearningSettings();
        }
        Config ls = fullConfig.getConfig("simulation.learningSettings");

        boolean circadianEnabled    = !ls.hasPath("circadianEnabled")    || ls.getBoolean("circadianEnabled");
        boolean consolidationEnabled = !ls.hasPath("consolidationEnabled") || ls.getBoolean("consolidationEnabled");

        List<ActionSelectionType> enabledFilters;
        if (ls.hasPath("enabledFilters")) {
            List<String> names = ls.getStringList("enabledFilters");
            enabledFilters = new ArrayList<>();
            for (String name : names) {
                enabledFilters.add(ActionSelectionType.valueOf(name));
            }
        } else {
            enabledFilters = LearningSettings.MASTER_FILTER_ORDER;
        }

        // Issue #57 — neuromodulatory expectancy loop (default-off).
        boolean expectancyEnabled = ls.hasPath("expectancyEnabled") && ls.getBoolean("expectancyEnabled");
        boolean neuromodulationEnabled = ls.hasPath("neuromodulationEnabled") && ls.getBoolean("neuromodulationEnabled");

        ExpectancyMode expectancyMode = ExpectancyMode.DISCRETE;
        if (ls.hasPath("expectancyMode")) {
            expectancyMode = ExpectancyMode.valueOf(ls.getString("expectancyMode").toUpperCase());
        }

        boolean actionTendencyEnabled = ls.hasPath("actionTendencyEnabled") && ls.getBoolean("actionTendencyEnabled");
        boolean orexinEnabled         = ls.hasPath("orexinEnabled")         && ls.getBoolean("orexinEnabled");
        boolean endocrineEnabled      = ls.hasPath("endocrineEnabled")      && ls.getBoolean("endocrineEnabled");

        // Issue #85 — default-ON (note the inverted shape vs the flags above): only an
        // explicit `tickGatedCognition = false` restores the pre-#85 per-delivery cycle,
        // which exists for the p85 baseline arm and nothing else.
        boolean tickGatedCognition = !ls.hasPath("tickGatedCognition") || ls.getBoolean("tickGatedCognition");

        // Issue #99 — focal drive replaces the per-cycle most-aroused emotion (default-off).
        boolean focalDriveEnabled = ls.hasPath("focalDriveEnabled") && ls.getBoolean("focalDriveEnabled");

        // Issue #110 — operant estimator fed by closed episodes (default-off). Requires the focal
        // drive; the LearningSettings constructor rejects the combination otherwise.
        boolean operantEstimatorEnabled = ls.hasPath("operantEstimatorEnabled") && ls.getBoolean("operantEstimatorEnabled");

        return new LearningSettings(circadianEnabled, consolidationEnabled, enabledFilters,
                expectancyEnabled, expectancyMode, neuromodulationEnabled, actionTendencyEnabled,
                orexinEnabled, endocrineEnabled, tickGatedCognition, focalDriveEnabled,
                operantEstimatorEnabled);
    }

    public Long getNumHolders() {
        return numHolders;
    }

    public void setNumHolders(Long numHolders) {
        this.numHolders = numHolders;
    }

    /** Root seed of every random stream in the run; see {@link Seeds}. */
    public long getSeed() {
        return seed;
    }

    /** True when the config gave no seed and one was generated (it is logged either way). */
    public boolean isSeedGenerated() {
        return seedGenerated;
    }

    public PositionFactory getPositionFactory() {
        return positionFactory;
    }

    public void setPositionFactory(PositionFactory positionFactory) {
        this.positionFactory = positionFactory;
    }

    public List<CreatureSetting> getCreatureSettings() {
        return creatureSettings;
    }

    public void setCreatureSettings(List<CreatureSetting> creatureSettings) {
        this.creatureSettings = creatureSettings;
    }

    public List<WorldObjectSetting> getWorldObjectSettings() {
        return worldObjectSettings;
    }

    public void setWorldObjectSettings(List<WorldObjectSetting> worldObjectSettings) {
        this.worldObjectSettings = worldObjectSettings;
    }

    public Point getWorldBoundaries() {
        return worldBoundaries;
    }

    public boolean isReposition() {
        return reposition;
    }

    public int getRepositionDelaySeconds() {
        return repositionDelaySeconds;
    }

    public int getMaxRuntimeMinutes() {
        return maxRuntimeMinutes;
    }

    public LearningSettings getLearningSettings() {
        return learningSettings;
    }

    private static WorldObjectType parseWorldObjectType(String name) {
        try {
            return FruitType.valueOf(name);
        } catch (IllegalArgumentException ignored) {}
        try {
            return PlantType.valueOf(name);
        } catch (IllegalArgumentException ignored) {}
        throw new IllegalArgumentException("Unknown world object type: " + name);
    }
}
