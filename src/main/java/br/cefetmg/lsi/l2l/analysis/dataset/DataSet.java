package br.cefetmg.lsi.l2l.analysis.dataset;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Created by felipe on 11/12/15.
 */
public class DataSet implements Serializable {
	private static final long serialVersionUID = -6300353668048673009L;

	private List<String> labels;

    private Map<String, Object[]> table;

    private final int rows;

    public DataSet(int size) {
        this.rows = size;
        labels = new ArrayList<>();
        table = new LinkedHashMap<>();
    }

    public void addSeries(String label, Object[] series) {
    	
        if (series.length != rows) {
            throw new IllegalArgumentException("The series rows must have the same rows number of dataset table");
        }

        labels.add(label);
        table.put(label, series);
    }

    public Object[] getSeries(String label) {
        return table.get(label);
    }

    public Object getValue(String label, int pos) {
        Object[] series = getSeries(label);

        if (series == null) {
            throw new IllegalArgumentException("Label " + label + " doesn\'t exist");
        }

        return series[pos];
    }

    public Object getValue(int row, int col) {
    	
        if (col >= getColumns()) {
            throw new IllegalArgumentException("Column exceeds dimension");
        }

        if (row >= getRows()) {
            throw new IllegalArgumentException("Row exceeds data set size");
        }

        String label = labels.get(col);
        Object [] series = table.get(label);

        return series[row];
    }

    public void setValue(String label, int pos, Object value) {
        Object [] series = getSeries(label);

        if (series == null) {
        	throw new IllegalArgumentException("Label " + label + " doesn\'t exist");
        }

        if (pos >= rows) {
            throw new IllegalArgumentException("Row exceeds data set size");
        }

        series[pos] = value;
    }

    public void setValue(int row, int col, Object value) {
    	
        if (col >= getColumns()) {
            throw new IllegalArgumentException("Column exceeds dimension");
        }

        if (row >= getRows()) {
            throw new IllegalArgumentException("Row exceeds data set size");
        }

        String label = labels.get(col);
        Object [] series = getSeries(label);
        series[row] = value;
    }

    public int getRows() {
        return rows;
    }

    public int getColumns() {
        return table.size();
    }

    public List<String> getLabels() {
        return labels;
    }
    
}
