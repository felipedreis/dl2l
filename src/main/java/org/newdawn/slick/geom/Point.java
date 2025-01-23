package org.newdawn.slick.geom;

import org.newdawn.slick.geom.Shape; 
import org.newdawn.slick.geom.Transform; 

/**
 * A single point shape
 * 
 * @author Kova
 */

class Point{}
class PointSlick extends Shape
{
    public PointSlick(double[] points){
        this.x = points[0];
        this.y = points[1];
        checkPoints();
    }

	/**
	 * Create a new point
	 * 
	 * @param x The x coordinate of the point
	 * @param y The y coordinate of the point
	 */
    public PointSlick(double x, double y)
    { 
        this.x = x; 
        this.y = y; 
        checkPoints(); 
    } 

    /**
     * @see Shape#transform(Transform)
     */
    public Shape transform(Transform transform) 
    { 
        double result[] = new double[points.length]; 
        transform.transform(points, 0, result, 0, points.length / 2); 
        
        return new PointSlick(points[0], points[1]);
    } 

    /**
     * @see Shape#createPoints()
     */
    protected void createPoints() 
    { 
        points = new double[2]; 
        points[0] = getX(); 
        points[1] = getY(); 
        
        maxX = x; 
        maxY = y; 
        minX = x; 
        minY = y; 
        
        findCenter(); 
        calculateRadius(); 
    } 

    /**
     * @see Shape#findCenter()
     */
    protected void findCenter() 
    { 
    	center = new double[2];
        center[0] = points[0]; 
        center[1] = points[1]; 
    } 

    /**
     * @see Shape#calculateRadius()
     */
    protected void calculateRadius() 
    { 
        boundingCircleRadius = 0; 
    } 
}