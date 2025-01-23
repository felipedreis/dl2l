package br.cefetmg.lsi.l2l.physics;

import org.newdawn.slick.geom.Shape;
import org.newdawn.slick.geom.Transform;

public class Arc extends Shape{

    public final double centerX;
    public final double centerY;
    public final double startAngle;
    public final double arcOpening;
    public final double radius;
    public final int segments;

    public Arc(double centerX, double centerY, double radius, double startAngle, double arcOpening, int segments){
        this.centerX = centerX;
        this.centerY = centerY;
        this.radius = radius;
        this.startAngle = startAngle;
        this.arcOpening = arcOpening;
        this.segments = segments;
    }


    protected void createPoints() {

        //Inicializa o vetor pontos com o maximo de pontos que vou precisar.
        points = new double[(segments+3) * 2];


        double segmentAngle = (double) (this.arcOpening / this.segments);


        for(int i=0; i<this.segments; i++){
            double angle = (double) segmentAngle*(i+1);
            points[i*2] = (double) ((double)centerX + (double)radius*((double) Math.cos((double)(Math.toRadians(this.startAngle+angle)))));

        }

        for(int i=0; i<this.segments; i++){
            double angle = (double) segmentAngle*(i+1);
            points[(i*2)+1] = (double) ((double)centerY + (double)radius*((double) - Math.sin((double)(Math.toRadians(this.startAngle+angle)))));

        }

        double[] points2 = new double[((segments+3) * 2)];

        System.arraycopy(points, 0, points2, 4, segments * 2);

        //1
        points2[0] = centerX;
        points2[1] = centerY;


        //2
        points2[2] = (double) ((double)centerX + (double)radius*((double)Math.cos((double)(Math.toRadians(this.startAngle)))));
        points2[3] = (double) ((double)centerY + (double)radius*((double) - Math.sin((double)(Math.toRadians(this.startAngle)))));

        //Ultimo
        points2[((segments+3) * 2)-2] = (double) ((double)centerX + (double)radius*((double)Math.cos((double)(Math.toRadians(this.startAngle+this.arcOpening)))));
        points2[((segments+3) * 2)-1] = (double) ((double)centerY + (double)radius*((double) - Math.sin((double)(Math.toRadians(this.startAngle+this.arcOpening)))));

        points = points2;

    }


    @Override
    public Shape transform(Transform arg0) {
        // TODO Auto-generated method stub
        return null;
    }
}