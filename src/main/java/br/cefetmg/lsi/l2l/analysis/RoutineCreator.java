package br.cefetmg.lsi.l2l.analysis;

import java.util.List;

import javax.persistence.EntityManager;

import br.cefetmg.lsi.l2l.analysis.extractor.*;
import br.cefetmg.lsi.l2l.common.SequentialId;

/**
 * This class is responsible for creating data extraction routines. When a new Extractor is created, the user just
 * have to register it in the appropriated method.
 *
 * @author Felipe Duarte dos Reis, felipeduarte@lsi.cefetmg.br
 */
public class RoutineCreator {

    /**
     * Data destine directory
     */
    private String dataDir;

    /**
     * Database connection
     */
    private EntityManager em;

    /**
     * Constructor. Only allowed creating routines with valid database connection
     * @param em Database connection, can't be null
     */
    public RoutineCreator(EntityManager em, String dataDir) {
    	
        if (em == null) {
            throw new NullPointerException();
        }

        this.em = em;
        this.dataDir = (dataDir == null) ? "" : dataDir;
    }

    /**
     * Create a sample extraction routine
     * @param ids sample ids
     * @return A Routine instance, with sample related extractors
     * @throws NullPointerException if ids is null
     */
    public Routine sampleRoutine(List<SequentialId> ids){
    	
        if (ids == null) {
            throw new NullPointerException();
        }

        return new Routine(
        		dataDir,
                new LifetimesExtractor(em, ids),
                new TraveledDistanceExtractor(em, ids),
                new EatenNutrientsExtractor(em, ids),
                new TracingExtractor(em, ids),
                new TotalEnvironmentalStimuliChanged(em, ids)
        );
    }

    /**
     * Create a creature extraction routine
     * @param id creature id
     * @return A routine instance with creature related extractors
     * @throws NullPointerException if id is null
     */
    public Routine creatureRoutine(SequentialId id) {
    	
        if (id == null) {
            throw new NullPointerException();
        }

        return new Routine(dataDir,
                new AccumulatedChoicesOverTimeExtractor(em, id),
                new ArousalHistoryExtractor(em, id),
                new BehaviouralEfficiencyExtractor(em, id),
                new ChoicesOverTimeExtractor(em, id),
                new AccEatenNutrientsOverTimeExtractor(em, id),
                new ProducedStimuliGroupedOverTimeExtractor(em, id)
        );
        
    }
    
}
