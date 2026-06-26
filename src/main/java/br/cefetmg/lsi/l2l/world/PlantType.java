package br.cefetmg.lsi.l2l.world;

public enum PlantType implements WorldObjectType {
    CACTUS(0.3, 0.7, 12);

    public final double passivePain;
    public final double activePain;
    public final double radius;

    PlantType(double passivePain, double activePain, double radius) {
        this.passivePain = passivePain;
        this.activePain  = activePain;
        this.radius      = radius;
    }
}
