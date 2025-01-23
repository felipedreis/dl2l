package br.cefetmg.lsi.l2l.common;

import br.cefetmg.lsi.l2l.physics.Geometry;
import org.newdawn.slick.geom.Rectangle;

import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;

public class QuadTree <T extends Geometry>{

    private static final Logger logger = Logger.getLogger(QuadTree.class.getName());

    private static final int MAX_OBJECTS = 10; // Maximum objects per node before subdivision
    private static final int MAX_LEVELS = 5; // Maximum depth of the tree

    enum NodePosition {NW, NE, SW, SE, ROOT};

    private int level;
    private Rectangle bounds;
    private Set<T> objects;
    private QuadTree[] nodes;
    private NodePosition position;

    private int size;

    public QuadTree(Rectangle bounds) {
        this(0, bounds, NodePosition.ROOT);
    }

    public QuadTree(int level, Rectangle bounds, NodePosition position) {
        this.level = level;
        this.bounds = bounds;
        this.size = 0;
        objects = new HashSet<>();
        this.nodes = new QuadTree[4]; // 4 child nodes (NW, NE, SW, SE)
        this.position = position;
    }

    public void clear() {
        objects.clear();
        for (int i = 0; i < 4; i++) {
            if (nodes[i] != null) {
                nodes[i].clear();
                nodes[i] = null;
            }
        }
    }

    public boolean insert(T obj) {
        logger.info("Inserting %s in node %s at level %s".formatted(obj, position, level));
        if (nodes[0] != null) { // If this node has subnodes
            int index = getIndex(obj);
            if (index != -1 && nodes[index].insert(obj)) {
                size++;
                return true;
            }
        }

        objects.add(obj);
        size++;

        if (objects.size() > MAX_OBJECTS && level < MAX_LEVELS) {
            logger.info("splitting node");
            if (nodes[0] == null) {
                subdivide();
            }

            List<T> objectsToMove = new ArrayList<>();
            // Redistribute existing objects to subnodes
            for (T objToMove : objects) {
                int index = getIndex(objToMove);
                if (index != -1) {
                    nodes[index].insert(objToMove);
                    objectsToMove.add(objToMove);
                }
            }

            objects.removeAll(objectsToMove);
        }

        return true;
    }

    public boolean remove(T obj) {
        if (objects.contains(obj)) {
            objects.remove(obj);
            size--;
            return true;
        }

        boolean removed = false;
        if (nodes[0] != null) {
            int index = getIndex(obj);
            if (index != -1 && nodes[index].remove(obj)) {
                size--;
                removed = true;
            }
        }

        return removed;
    }

    public List<T> query(Rectangle range) {
        List<T> foundObjects = new ArrayList<>();
        if (bounds.intersects(range)) {
            for (T obj : objects) {
                if (range.contains(obj.getX(), obj.getY())) {
                    foundObjects.add(obj);
                }
            }

            if (nodes[0] != null) {
                for (int i = 0; i < 4; i++) {
                    foundObjects.addAll(nodes[i].query(range));
                }
            }
        }
        return foundObjects;
    }

    public int size(){
        return size;
    }

    private void subdivide() {
        double x = bounds.getX();
        double y = bounds.getY();
        double w = bounds.getWidth() / 2;
        double h = bounds.getHeight() / 2;

        nodes[0] = new QuadTree(level + 1, new Rectangle(x, y, w, h), NodePosition.NW); // NW
        nodes[1] = new QuadTree(level + 1, new Rectangle(x + w, y, w, h), NodePosition.NE); // NE
        nodes[2] = new QuadTree(level + 1, new Rectangle(x, y + h, w, h), NodePosition.SW); // SW
        nodes[3] = new QuadTree(level + 1, new Rectangle(x + w, y + h, w, h), NodePosition.SE); // SE
    }

    private int getIndex(T obj) {
        double x = obj.getX();
        double y = obj.getY();
        double midX = bounds.getX() + bounds.getWidth() / 2;
        double midY = bounds.getY() + bounds.getHeight() / 2;

        if (x < midX && y < midY) {
            return 0; // NW
        } else if (x >= midX && y < midY) {
            return 1; // NE
        } else if (x < midX && y >= midY) {
            return 2; // SW
        } else if (x >= midX && y >= midY) {
            return 3; // SE
        }

        return -1; // Object outside of this node's bounds
    }

    @Override
    public String toString() {
        return "QuadTree{" +
                "level=" + level +
                ", bounds=" + bounds +
                ", size=" + size +
                '}';
    }

    /* Helper class to represent a rectangular area
    private static class Rectangle {
        private double x, y, width, height;

        public Rectangle(double x, double y, double width, double height) {
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;
        }

        public double getX() {
            return x;
        }

        public double getY() {
            return y;
        }

        public double getWidth() {
            return width;
        }

        public double getHeight() {
            return height;
        }

        public boolean intersects(Rectangle other) {
            return !(
                    this.x + this.width < other.x ||
                            this.x > other.x + other.width ||
                            this.y + this.height < other.y ||
                            this.y > other.y + other.height
            );
        }

        public boolean contains(double x, double y) {
            return x >= this.x && x <= this.x + this.width && y >= this.y && y <= this.y + this.height;
        }
    } */
}