package br.cefetmg.lsi.l2l.cluster.settings;

import br.cefetmg.lsi.l2l.common.Point;
import br.cefetmg.lsi.l2l.world.FruitType;
import br.cefetmg.lsi.l2l.world.PositionFactory;
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

    private boolean noUI;

    private boolean reposition;

    public Simulation(){}

    public Simulation(Config config) {
        Config fullConfig = config.withFallback(ConfigFactory.load("simulation"));

        Config worldSize = fullConfig.getConfig("simulation.worldSize");

        noUI = fullConfig.getBoolean("simulation.noUI");

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
            /// TODO if a world object is not a fruit (may be a predator, or a toy, or something else) it will crash
            worldObjectSetting.setType(FruitType.valueOf(worldObjectConfig.getString("objectType")));
            worldObjectSettings.add(worldObjectSetting);
        }

        reposition = fullConfig.getBoolean("simulation.reposition");
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

    public boolean isNoUI() {
        return noUI;
    }

    public void setNoUI(boolean noUI) {
        this.noUI = noUI;
    }

    public boolean isReposition() {
        return reposition;
    }
}
