package br.cefetmg.lsi.l2l.analysis;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;

import javax.persistence.EntityManager;
import javax.persistence.EntityManagerFactory;
import javax.persistence.Persistence;

import br.cefetmg.lsi.l2l.common.SequentialId;

/**
 * Created by felipe on 11/02/16.
 */
public class DataAnalyser {

    private String dataDir;

    private EntityManager em;

    public DataAnalyser(EntityManager em, String dataDir) {
        this.em = em;
        this.dataDir = dataDir;
    }

    public CompletableFuture run() {
        RoutineCreator creator = new RoutineCreator(em, dataDir);

        List<SequentialId> result = em.createNamedQuery("CreatureState.findAllCreatureIds", SequentialId.class)
                .getResultList();

        // the distinct clause do not work. Why? Just God knows
        // so I added the result to a set and get just distinct ids.
        Set<SequentialId> ids = new HashSet<>(result);

        List<CompletableFuture> futures = new ArrayList<>();

        Routine sampleRoutine = creator.sampleRoutine(new ArrayList<>(ids));

        futures.addAll(sampleRoutine.getFutures());

        for (SequentialId id : ids) {
            System.out.println("Creating routine for " + id);
            
            futures.addAll(creator.creatureRoutine(id).getFutures());
        }
        return CompletableFuture.allOf(futures.stream().toArray(CompletableFuture[]::new));
    }
    
}
