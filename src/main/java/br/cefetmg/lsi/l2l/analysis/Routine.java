package br.cefetmg.lsi.l2l.analysis;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;

import br.cefetmg.lsi.l2l.analysis.dataset.DataSetSaver;
import br.cefetmg.lsi.l2l.analysis.extractor.Extractor;

/**
 * A routine is a class that encapsulate extractors and creates a default handler to the received data sets.
 * This is a default routine and the behaviour after receiving a data set is saving it to a file. The process of extracting
 * and save is made asynchronously, since most of data is independent and may be handled at same time.
 *
 * @author Felipe Duarte dos Reis (felipeduarte@lsi.cefetmg.br)
 */
public class Routine {

    /**
     * Data destination directory
     */
    private String dataDir;

    /**
     * Data extractors
     */
    private Extractor[] extractors;

    /**
     * Default constructor, receive some extractors
     * @param extractors instance of extractors
     */
    public Routine(String dataDir, Extractor... extractors) {
        this.dataDir = dataDir;
        this.extractors = extractors;
    }

    /**
     * Call extract of each Extractor in a async future, then creates a DataSetSaver for each extractor and call
     * async the save method for each one too.
     *
     * @thorw NullPointerException if a extractor is null
     * @return
     */
    public List<CompletableFuture> getFutures() {
        CompletableFuture[] futures = new CompletableFuture[extractors.length];

        for (int i = 0; i < extractors.length; ++i) {
            Extractor e = extractors[i];

            if (e == null) {
                throw new IllegalStateException("There's null extractors in this routine");
            }

            futures[i] = CompletableFuture.supplyAsync(e::getDataSet)
                    .thenApply(d -> new DataSetSaver(d, dataDir + "/" + e.getName()))
                    .thenAccept(DataSetSaver::save)
                    .thenRun(() -> Logger.getLogger(Routine.class.getName()).info(e.getName() + " saved with success"));
        }

        return Arrays.asList(futures);
    }

    public void setExtractors(Extractor[] extractors) {
        this.extractors = extractors;
    }

    public Extractor[] getExtractors() {
        return extractors;
    }
    
}
