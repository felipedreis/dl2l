package br.cefetmg.lsi.l2l.physics;

import org.newdawn.slick.geom.Shape;
import org.newdawn.slick.geom.Transform;

public class Arc extends Shape{

    private float centerX;
    private float centerY;
    private float startAngle;
    private float arcOpening;
    private float radius;
    private int segments;

    public Arc(float centerX, float centerY, float radius, float startAngle, float arcOpening, int segments){
        this.centerX = centerX;
        this.centerY = centerY;
        this.radius = radius;
        this.startAngle = startAngle;
        this.arcOpening = arcOpening;
        this.segments = segments;
    }


    protected void createPoints() {

        //Inicializa o vetor pontos com o maximo de pontos que vou precisar.
        points = new float[(segments+3) * 2];


        float segmentAngle = (float) (this.arcOpening / this.segments);


        for(int i=0; i<this.segments; i++){
            float angle = (float) segmentAngle*(i+1);
            points[i*2] = (float) ((float)centerX + (float)radius*((float) Math.cos((float)(Math.toRadians(this.startAngle+angle)))));

        }

        for(int i=0; i<this.segments; i++){
            float angle = (float) segmentAngle*(i+1);
            points[(i*2)+1] = (float) ((float)centerY + (float)radius*((float) - Math.sin((float)(Math.toRadians(this.startAngle+angle)))));

        }

        float[] points2 = new float[((segments+3) * 2)];

        System.arraycopy(points, 0, points2, 4, segments * 2);

        //1
        points2[0] = centerX;
        points2[1] = centerY;


        //2
        points2[2] = (float) ((float)centerX + (float)radius*((float)Math.cos((float)(Math.toRadians(this.startAngle)))));
        points2[3] = (float) ((float)centerY + (float)radius*((float) - Math.sin((float)(Math.toRadians(this.startAngle)))));

        //Ultimo
        points2[((segments+3) * 2)-2] = (float) ((float)centerX + (float)radius*((float)Math.cos((float)(Math.toRadians(this.startAngle+this.arcOpening)))));
        points2[((segments+3) * 2)-1] = (float) ((float)centerY + (float)radius*((float) - Math.sin((float)(Math.toRadians(this.startAngle+this.arcOpening)))));

        points = points2;

    }


    @Override
    public Shape transform(Transform arg0) {
        // TODO Auto-generated method stub
        return null;
    }
}