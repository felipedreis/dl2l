package org.newdawn.slick.geom;

import java.io.Serializable;

/**
 * A two dimensional vector
 * 
 * @author Kevin Glass
 */
public strictfp class Vector2f implements Serializable {
	/** The version ID for this class  */
	private static final long serialVersionUID = 1339934L;
	
	/** The x component of this vector */
	public double x;
	/** The y component of this vector */
	public double y;
	
	/**
	 * Create an empty vector
	 */
	public Vector2f() {
	}

	/**
	 * Create a vector based on the contents of a coordinate array
	 * 
	 * @param coords The coordinates array, index 0 = x, index 1 = y
	 */
	public Vector2f(double[] coords) {
		x = coords[0];
		y = coords[1];
	}
	
	/**
	 * Create a new vector based on an angle
	 * 
	 * @param theta The angle of the vector in degrees
	 */
	public Vector2f(double theta) {
		x = 1;
		y = 0;
		setTheta(theta);
	}

	/**
	 * Calculate the components of the vectors based on a angle
	 * 
	 * @param theta The angle to calculate the components from (in degrees)
	 */
	public void setTheta(double theta) {
		// Next lines are to prevent numbers like -1.8369701E-16
		// when working with negative numbers
		if ((theta < -360) || (theta > 360)) {
			theta = theta % 360;
		}
		if (theta < 0) {
			theta = 360 + theta;
		}
		double oldTheta = getTheta();
		if ((theta < -360) || (theta > 360)) {
			oldTheta = oldTheta % 360;
		}
		if (theta < 0) {
			oldTheta = 360 + oldTheta;
		}

		double len = length();
		x = len * (double) FastTrig.cos(StrictMath.toRadians(theta));
		y = len * (double) FastTrig.sin(StrictMath.toRadians(theta));
		
//		x = x / (double) FastTrig.cos(StrictMath.toRadians(oldTheta))
//				* (double) FastTrig.cos(StrictMath.toRadians(theta));
//		y = x / (double) FastTrig.sin(StrictMath.toRadians(oldTheta))
//				* (double) FastTrig.sin(StrictMath.toRadians(theta));
	} 
	
	/**
	 * Adjust this vector by a given angle
	 * 
	 * @param theta
	 *            The angle to adjust the angle by (in degrees)
	 * @return This vector - useful for chaining operations
	 *           
	 */
	public Vector2f add(double theta) {
		setTheta(getTheta() + theta);
		
		return this;
	}

	/**
	 * Adjust this vector by a given angle
	 * 
	 * @param theta The angle to adjust the angle by (in degrees)
	 * @return This vector - useful for chaining operations
	 */
	public Vector2f sub(double theta) {
		setTheta(getTheta() - theta);
		
		return this;
	}
	
	/**
	 * Get the angle this vector is at
	 * 
	 * @return The angle this vector is at (in degrees)
	 */
	public double getTheta() {
		double theta = StrictMath.toDegrees(StrictMath.atan2(y, x));
		if ((theta < -360) || (theta > 360)) {
			theta = theta % 360;
		}
		if (theta < 0) {
			theta = 360 + theta;
		}

		return theta;
	} 
	
	/**
	 * Get the x component
	 * 
	 * @return The x component
	 */
	public double getX() {
		return x;
	}

	/**
	 * Get the y component
	 * 
	 * @return The y component
	 */
	public double getY() {
		return y;
	}
	
	/**
	 * Create a new vector based on another
	 * 
	 * @param other The other vector to copy into this one
	 */
	public Vector2f(Vector2f other) {
		this(other.getX(),other.getY());
	}
	
	/**
	 * Create a new vector 
	 * 
	 * @param x The x component to assign
	 * @param y The y component to assign
	 */
	public Vector2f(double x, double y) {
		this.x = x;
		this.y = y;
	}

	/**
	 * Set the value of this vector
	 * 
	 * @param other The values to set into the vector
	 */
	public void set(Vector2f other) {
		set(other.getX(),other.getY());
	}
	
	/**
	 * Dot this vector against another
	 * 
	 * @param other The other vector dot agianst
	 * @return The dot product of the two vectors
	 */
	public double dot(Vector2f other) {
		return (x * other.getX()) + (y * other.getY());
	}
	
	/**
	 * Set the values in this vector
	 * 
	 * @param x The x component to set
	 * @param y The y component to set
	 * @return This vector - useful for chaining operations
	 */
	public Vector2f set(double x, double y) { 
		this.x = x; 
		this.y = y; 
		
		return this;
	}
	
	/**
	 * A vector perpendicular to this vector.
	 *
	 * @return a vector perpendicular to this vector
	 */
	public Vector2f getPerpendicular() {
	   return new Vector2f(-y, x);
	}
	
	/**
	 * Set the values in this vector
	 * 
	 * @param pt The pair of values to set into the vector
	 * @return This vector - useful for chaining operations
	 */
	public Vector2f set(double[] pt) {
		return set(pt[0], pt[1]);
	}
	
	/**
	 * Negate this vector 
	 * 
	 * @return A copy of this vector negated
	 */
	public Vector2f negate() {
		return new Vector2f(-x, -y); 
	}

	/**
	 * Negate this vector without creating a new copy
	 * 
	 * @return This vector - useful for chaning operations
	 */
	public Vector2f negateLocal() {
		x = -x;
		y = -y;
		
		return this;
	}
	
	/**
	 * Add a vector to this vector
	 * 
	 * @param v The vector to add
	 * @return This vector - useful for chaning operations
	 */
	public Vector2f add(Vector2f v)
	{
		x += v.getX(); 
		y += v.getY();
		
		return this;
	}
	
	/**
	 * Subtract a vector from this vector
	 * 
	 * @param v The vector subtract
	 * @return This vector - useful for chaining operations
	 */
	public Vector2f sub(Vector2f v)
	{
		x -= v.getX(); 
		y -= v.getY();
		
		return this;
	}

	/**
	 * Scale this vector by a value
	 * 
	 * @param a The value to scale this vector by
	 * @return This vector - useful for chaining operations
	 */
	public Vector2f scale(double a)
	{
		x *= a; 
		y *= a;
		
		return this;
	}

	/**
	 * Normalise the vector
	 * 
	 * @return This vector - useful for chaning operations
	 */
	public Vector2f normalise() {
		double l = length();
		
		if (l == 0) {
			return this;
		}
		
		x /= l;
		y /= l;
		return this;
	}
	
	/**
     * The normal of the vector
     * 
     * @return A unit vector with the same direction as the vector
     */
    public Vector2f getNormal() {
	   Vector2f cp = copy();
	   cp.normalise();
	   return cp;
    } 
    
	/**
	 * The length of the vector squared
	 * 
	 * @return The length of the vector squared
	 */
	public double lengthSquared() {
		return (x * x) + (y * y);
	}
	
	/**
	 * Get the length of this vector
	 * 
	 * @return The length of this vector
	 */
	public double length() 
	{
		return (double) Math.sqrt(lengthSquared());
	}
	
	/**
	 * Project this vector onto another
	 * 
	 * @param b The vector to project onto
	 * @param result The projected vector
	 */
	public void projectOntoUnit(Vector2f b, Vector2f result) {
		double dp = b.dot(this);
		
		result.x = dp * b.getX();
		result.y = dp * b.getY();
		
	}
	
	/**
	 * Return a copy of this vector
	 * 
	 * @return The new instance that copies this vector
	 */
	public Vector2f copy() {
		return new Vector2f(x,y);
	}
	
	/**
	 * @see Object#toString()
	 */
	public String toString() {
		return "[Vector2f "+x+","+y+" ("+length()+")]";
	}
	
	/**
	 * Get the distance from this point to another
	 * 
	 * @param other The other point we're measuring to
	 * @return The distance to the other point
	 */
	public double distance(Vector2f other) {
		return (double) Math.sqrt(distanceSquared(other));
	}
	
	/**
	 * Get the distance from this point to another, squared. This
	 * can sometimes be used in place of distance and avoids the 
	 * additional sqrt.
	 * 
	 * @param other The other point we're measuring to 
	 * @return The distance to the other point squared
	 */
	public double distanceSquared(Vector2f other) {
		double dx = other.getX() - getX();
		double dy = other.getY() - getY();
		
		return (double) (dx*dx)+(dy*dy);
	}
	
	/**
	 * @see Object#hashCode()
	 */
	public int hashCode() {
        return 997 * ((int)x) ^ 991 * ((int)y); //large primes! 
	}
	
	/**
	 * @see Object#equals(Object)
	 */
	public boolean equals(Object other) {
		if (other instanceof Vector2f) {
			Vector2f o = ((Vector2f) other);
			return (o.x == x) && (o.y == y);
		}
		
		return false;
	}
}
