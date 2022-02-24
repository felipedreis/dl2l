package br.cefetmg.lsi.l2l.world;

/**
 * Created by felipe on 06/01/17.
 */
public enum FruitType implements WorldObjectType {
    RED_APPLE(0.2, 10),
    GREEN_APPLE(0.5, 15),
    GRAY_APPLE(0, 10);

    public double caloricValue;
    public double radius;

    FruitType(double caloricValue, double radius) {
        this.caloricValue = caloricValue;
        this.radius = radius;
    }
}
