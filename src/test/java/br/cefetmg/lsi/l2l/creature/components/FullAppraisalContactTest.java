package br.cefetmg.lsi.l2l.creature.components;

import br.cefetmg.lsi.l2l.common.Constants;
import br.cefetmg.lsi.l2l.world.FruitType;
import br.cefetmg.lsi.l2l.world.PlantType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Issue #96: contact range that keeps EAT affordable for a creature stepping up to MAX_STEP. */
public class FullAppraisalContactTest {

    @Test
    public void fruitContactIsMouthRadiusPlusFruitRadius() {
        for (FruitType fruit : FruitType.values()) {
            assertEquals(Constants.DEFAULT_MOUTH_RADIUS + fruit.radius,
                    FullAppraisal.contactDistance(fruit), 1e-9);
        }
    }

    @Test
    public void plantContactIsMouthRadiusPlusPlantRadius() {
        for (PlantType plant : PlantType.values()) {
            assertEquals(Constants.DEFAULT_MOUTH_RADIUS + plant.radius,
                    FullAppraisal.contactDistance(plant), 1e-9);
        }
    }

    @Test
    public void contactRangeIsWiderThanOneMaxStep() {
        // A creature that closes in at MAX_STEP per cycle must land inside contact range at
        // least once before it passes the target.
        for (FruitType fruit : FruitType.values()) {
            assertTrue(FullAppraisal.contactDistance(fruit) * 2 >= Constants.MAX_STEP);
        }
    }
}
