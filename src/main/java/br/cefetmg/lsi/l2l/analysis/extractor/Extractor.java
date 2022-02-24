package br.cefetmg.lsi.l2l.analysis.extractor;

import java.util.logging.Level;
import java.util.logging.Logger;

import javax.persistence.EntityManager;

import br.cefetmg.lsi.l2l.analysis.dataset.DataSet;

/**
 * Created by felipe on 11/12/15.
 */
public abstract class Extractor {

    public static final double MILLIS_TO_HOURS = 2.777777778e-7;
    public static final double MILLIS_TO_MINUTES = 1.666666667e-5;
    public static final double MILLIS_TO_SECONDS = 1.000000000e-3;

    protected EntityManager em;

    public Extractor(){
    }

    public Extractor(EntityManager em) {
        this.em = em;
    }

    public abstract DataSet extract();

    public EntityManager getEm() {
        return em;
    }

    public void setEm(EntityManager em) {
        this.em = em;
    }

    public abstract String getName();

    public DataSet getDataSet() {
    	
        try {
            return extract();
        } catch (Exception ex) {
            Logger.getLogger(getClass().getName()).log(Level.SEVERE, ex.getMessage(), ex);
        }

        return null;
    }
    
}
