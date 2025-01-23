package org.newdawn.slick.geom;

/**
 * A triangulator implementation that splits the triangules of another, subdividing
 * to give a higher tesselation - and hence smoother transitions.
 * 
 * @author kevin
 */
public class OverTriangulator implements Triangulator {
	/** The triangles data */
	private double[][] triangles;
	
	/**
	 * Create a new triangulator
	 * 
	 * @param tris The original set of triangles to be sub-dividied
	 */
	public OverTriangulator(Triangulator tris) {
		triangles = new double[tris.getTriangleCount()*6*3][2];
		
		int tcount = 0;
		for (int i=0;i<tris.getTriangleCount();i++) {
			double cx = 0;
			double cy = 0;
			for (int p = 0;p < 3;p++) {
				double[] pt = tris.getTrianglePoint(i, p);
				cx += pt[0];
				cy += pt[1];
			}
			
			cx /= 3;
			cy /= 3;
			
			for (int p = 0;p < 3;p++) {
				int n = p +1;
				if (n > 2) {
					n = 0;
				}
				
				double[] pt1 = tris.getTrianglePoint(i, p);
				double[] pt2 = tris.getTrianglePoint(i, n);

				pt1[0] = (pt1[0] + pt2[0]) / 2;
				pt1[1] = (pt1[1] + pt2[1]) / 2;
				
				triangles[(tcount *3) + 0][0] = cx;
				triangles[(tcount *3) + 0][1] = cy;
				triangles[(tcount *3) + 1][0] = pt1[0];
				triangles[(tcount *3) + 1][1] = pt1[1];
				triangles[(tcount *3) + 2][0] = pt2[0];
				triangles[(tcount *3) + 2][1] = pt2[1];
				tcount++;
			}
			
			for (int p = 0;p < 3;p++) {
				int n = p +1;
				if (n > 2) {
					n = 0;
				}
				
				double[] pt1 = tris.getTrianglePoint(i, p);
				double[] pt2 = tris.getTrianglePoint(i, n);
				
				pt2[0] = (pt1[0] + pt2[0]) / 2;
				pt2[1] = (pt1[1] + pt2[1]) / 2;
				
				triangles[(tcount *3) + 0][0] = cx;
				triangles[(tcount *3) + 0][1] = cy;
				triangles[(tcount *3) + 1][0] = pt1[0];
				triangles[(tcount *3) + 1][1] = pt1[1];
				triangles[(tcount *3) + 2][0] = pt2[0];
				triangles[(tcount *3) + 2][1] = pt2[1];
				tcount++;
			}
		}
	}
	
	/**
	 * @see Triangulator#addPolyPoint(double, double)
	 */
	public void addPolyPoint(double x, double y) {
	}

	/**
	 * @see Triangulator#getTriangleCount()
	 */
	public int getTriangleCount() {
		return triangles.length / 3;
	}

	/**
	 * @see Triangulator#getTrianglePoint(int, int)
	 */
	public double[] getTrianglePoint(int tri, int i) {
		double[] pt = triangles[(tri * 3)+i];
	
		return new double[] {pt[0],pt[1]};
	}

	/**
	 * @see Triangulator#startHole()
	 */
	public void startHole() {
	}

	/**
	 * @see Triangulator#triangulate()
	 */
	public boolean triangulate() {
		return true;
	}

}
