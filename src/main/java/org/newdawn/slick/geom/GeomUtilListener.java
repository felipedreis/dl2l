package org.newdawn.slick.geom;

/**
 * Debug listener for notifications assocaited with geometry utilities
 * 
 * @author kevin
 */
public interface GeomUtilListener {
	/**
	 * Notification that a point was excluded from geometry 
	 * 
	 * @param x The x coordinate of the point
	 * @param y The y coordinate of the point
	 */
	public void pointExcluded(double x, double y);

	/**
	 * Notification that a point was intersected between two geometries 
	 * 
	 * @param x The x coordinate of the point
	 * @param y The y coordinate of the point
	 */
	public void pointIntersected(double x, double y);

	/**
	 * Notification that a point was used to build a new geometry
	 * 
	 * @param x The x coordinate of the point
	 * @param y The y coordinate of the point
	 */
	public void pointUsed(double x, double y);
}
