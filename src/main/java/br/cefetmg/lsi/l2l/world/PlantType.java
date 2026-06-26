package br.cefetmg.lsi.l2l.world;

public enum PlantType implements WorldObjectType {
    CACTUS(0.3, 0.7, 0.0, 12, false),
    ALOE(0.0, 0.0, 0.5, 10, true);

    public final double passivePain;
    public final double activePain;
    public final double healAmount;
    public final double radius;
    public final boolean consumable;

    PlantType(double passivePain, double activePain, double healAmount, double radius, boolean consumable) {
        this.passivePain = passivePain;
        this.activePain  = activePain;
        this.healAmount  = healAmount;
        this.radius      = radius;
        this.consumable  = consumable;
    }
}
