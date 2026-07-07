package br.cefetmg.lsi.l2l.cluster.settings;

import br.cefetmg.lsi.l2l.common.Point;
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

    private LearningSettings learningSettings;

    public Simulation(){}

    public Simulation(Config config) {
        Config fullConfig = config.withFallback(ConfigFactory.load("simulation"));

        Config worldSize = fullConfig.getConfig("simulation.worldSize");

        worldBoundaries = new Point(worldSize.getDouble("width"), worldSize.getDouble("height"));

        numHolders = fullConfig.getLong("simulation.holders");

        try {
            positionFactory = (PositionFactory) getClass().getClassLoader()
                    .loadClass(fullConfig.getString("simulation.positionFactory"))
                    .getConstructor(Point.class).newInstance(worldBoundaries);

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

        return new LearningSettings(circadianEnabled, consolidationEnabled, enabledFilters,
                expectancyEnabled, expectancyMode, neuromodulationEnabled, actionTendencyEnabled);
    }

    public Long getNumHolders() {
        return numHolders;
    }

    public void setNumHolders(Long numHolders) {
        this.numHolders = numHolders;
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
