package br.cefetmg.lsi.l2l.world;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class WorldObjectTypeTest {

    // --- FruitType ---

    @Test
    void rotten_apple_has_negative_caloric_value() {
        assertTrue(FruitType.ROTTEN_APPLE.caloricValue < 0,
                "ROTTEN_APPLE caloric value must be negative so hunger rises on eat");
    }

    @Test
    void good_apples_have_positive_caloric_value() {
        assertTrue(FruitType.RED_APPLE.caloricValue > 0);
        assertTrue(FruitType.GREEN_APPLE.caloricValue > 0);
    }

    @Test
    void gray_apple_is_nutritionally_neutral() {
        assertEquals(0.0, FruitType.GRAY_APPLE.caloricValue);
    }

    // --- PlantType ---

    @Test
    void cactus_causes_passive_and_active_pain() {
        assertTrue(PlantType.CACTUS.passivePain > 0, "Cactus must hurt on passive collision");
        assertTrue(PlantType.CACTUS.activePain > 0,  "Cactus must hurt on deliberate EAT");
        assertTrue(PlantType.CACTUS.activePain > PlantType.CACTUS.passivePain,
                "Deliberate EAT should be more painful than passive touch");
    }

    @Test
    void cactus_is_not_consumable() {
        assertFalse(PlantType.CACTUS.consumable, "Cactus must be permanent (not consumed)");
        assertEquals(0.0, PlantType.CACTUS.healAmount);
    }

    @Test
    void aloe_heals_and_is_consumed() {
        assertTrue(PlantType.ALOE.healAmount > 0, "Aloe must reduce pain when eaten");
        assertTrue(PlantType.ALOE.consumable,     "Aloe must be consumed on eat");
        assertEquals(0.0, PlantType.ALOE.passivePain, "Aloe must not cause pain on touch");
        assertEquals(0.0, PlantType.ALOE.activePain,  "Aloe must not cause pain on eat");
    }

    // --- Config parsing (Simulation.parseWorldObjectType equivalent) ---

    @Test
    void fruit_types_resolve_by_name() {
        assertEquals(FruitType.RED_APPLE,    FruitType.valueOf("RED_APPLE"));
        assertEquals(FruitType.ROTTEN_APPLE, FruitType.valueOf("ROTTEN_APPLE"));
    }

    @Test
    void plant_types_resolve_by_name() {
        assertEquals(PlantType.CACTUS, PlantType.valueOf("CACTUS"));
        assertEquals(PlantType.ALOE,   PlantType.valueOf("ALOE"));
    }
}
