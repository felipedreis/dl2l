package org.newdawn.slick.geom;

import java.util.ArrayList;

/**
 * An ellipse meeting the <code>Shape</code> contract. The ellipse is actually an approximation using 
 * a series of points generated around the contour of the ellipse.
 * 
 * @author Mark
 */
public class Ellipse extends Shape {
    /**
     * Default number of segments to draw this ellipse with
     */
    protected static final int DEFAULT_SEGMENT_COUNT = 50;
    
    /**
     * The number of segments for graphical representation.
     */
    private int segmentCount;
    /**
     * horizontal radius
     */
    private double radius1;
    /**
     * vertical radius
     */
    private double radius2;

    /**
     * Creates a new Ellipse object.
     *
     * @param centerPointX x coordinate of the center of the ellipse
     * @param centerPointY y coordinate of the center of the ellipse
     * @param radius1 horizontal radius
     * @param radius2 vertical radius
     */
    public Ellipse(double centerPointX, double centerPointY, double radius1, double radius2) {
        this(centerPointX, centerPointY, radius1, radius2, DEFAULT_SEGMENT_COUNT);
    }

    /**
     * Creates a new Ellipse object.
     *
     * @param centerPointX x coordinate of the center of the ellipse
     * @param centerPointY y coordinate of the center of the ellipse
     * @param radius1 horizontal radius
     * @param radius2 vertical radius
     * @param segmentCount how fine to make the ellipse.
     */
    public Ellipse(double centerPointX, double centerPointY, double radius1, double radius2, int segmentCount) {
        this.x = centerPointX - radius1;
        this.y = centerPointY - radius2;
        this.radius1 = radius1;
        this.radius2 = radius2;
        this.segmentCount = segmentCount;
        checkPoints();
    }

    /**
     * Change the shape of this Ellipse
     * 
     * @param radius1 horizontal radius
     * @param radius2 vertical radius
     */
    public void setRadii(double radius1, double radius2) {
    	setRadius1(radius1);
    	setRadius2(radius2);
    }

    /**
     * Get the horizontal radius of the ellipse
     * 
     * @return The horizontal radius of the ellipse
     */
    public double getRadius1() {
        return radius1;
    }

    /**
     * Set the horizontal radius of the ellipse
     * 
     * @param radius1 The horizontal radius to set
     */
    public void setRadius1(double radius1) {
    	if (radius1 != this.radius1) {
	        this.radius1 = radius1;
	        pointsDirty = true;
    	}
    }

    /**
     * Get the vertical radius of the ellipse
     * 
     * @return The vertical radius of the ellipse
     */
    public double getRadius2() {
        return radius2;
    }

    /**
     * Set the vertical radius of the ellipse
     * 
     * @param radius2 The vertical radius to set
     */
    public void setRadius2(double radius2) {
    	if (radius2 != this.radius2) {
	        this.radius2 = radius2;
	        pointsDirty = true;
    	}
    }

    /**
     * Generate the points to outline this ellipse.
     *
     */
    protected void createPoints() {
        ArrayList tempPoints = new ArrayList();

        maxX = -Float.MIN_VALUE;
        maxY = -Float.MIN_VALUE;
        minX = Float.MAX_VALUE;
        minY = Float.MAX_VALUE;

        double start = 0;
        double end = 359;
        
        double cx = x + radius1;
        double cy = y + radius2;
        
        int step = 360 / segmentCount;
        
        for (double a=start;a<=end+step;a+=step) {
            double ang = a;
            if (ang > end) {
                ang = end;
            }
            double newX = (double) (cx + (FastTrig.cos(Math.toRadians(ang)) * radius1));
            double newY = (double) (cy + (FastTrig.sin(Math.toRadians(ang)) * radius2));

            if(newX > maxX) {
                maxX = newX;
            }
            if(newY > maxY) {
                maxY = newY;
            }
            if(newX < minX) {
            	minX = newX;
            }
            if(newY < minY) {
            	minY = newY;
            }
            
            tempPoints.add(new Float(newX));
            tempPoints.add(new Float(newY));
        }
        points = new double[tempPoints.size()];
        for(int i=0;i<points.length;i++) {
            points[i] = ((Float)tempPoints.get(i)).doubleValue();
        }
    }

    /**
     * @see Shape#transform(Transform)
     */
    public Shape transform(Transform transform) {
        checkPoints();
        
        Polygon resultPolygon = new Polygon();
        
        double result[] = new double[points.length];
        transform.transform(points, 0, result, 0, points.length / 2);
        resultPolygon.points = result;
        resultPolygon.checkPoints();

        return resultPolygon;
    }

    /**
     * @see Shape#findCenter()
     */
    protected void findCenter() {
        center = new double[2];
        center[0] = x + radius1;
        center[1] = y + radius2;
    }

    /**
     * @see Shape#calculateRadius()
     */
    protected void calculateRadius() {
        boundingCircleRadius = (radius1 > radius2) ? radius1 : radius2;
    }
}
