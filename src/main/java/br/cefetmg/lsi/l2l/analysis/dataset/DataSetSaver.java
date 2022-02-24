package br.cefetmg.lsi.l2l.analysis.dataset;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.Iterator;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Created by felipe on 05/02/16.
 */
public class DataSetSaver {

    private String filename;

    private DataSet dataSet;

    public DataSetSaver(DataSet dataSet, String filename) {
        this.dataSet = dataSet;
        this.filename = filename;
    }

    public void save() {

        try {
            File file = new File(filename + ".csv");

            if(!file.exists()) {
                file.getParentFile().mkdirs();
                file.createNewFile();
            } else {
                file.delete();
                file.getParentFile().mkdirs();
                file.createNewFile();
            }

            int lines = dataSet.getRows();
            String label;
            BufferedWriter buffer = new BufferedWriter(new FileWriter(file));
            List<String> labels = dataSet.getLabels();

            Iterator<String> it = labels.iterator();
            while (it.hasNext()) {            	
                label = it.next();
                
                if (it.hasNext()) {
                    buffer.write(label + ",");
                } else {
                    buffer.write(label);
                }
                
            }

            buffer.newLine();

            for (int i = 0; i < lines; ++i) {
            	
                it = labels.iterator();
                while (it.hasNext()) {
                    label = it.next();
                    Object value = dataSet.getValue(label, i);
                    String v = (value == null) ? "" : value.toString();

                    if (it.hasNext()) {
                        buffer.write(v + ",");
                    } else {
                        buffer.write(v.toString());
                    }
                    
                }

                buffer.newLine();
            }
            
            buffer.close();
        } catch (IOException ex) {
            Logger.getLogger(DataSetSaver.class.getName()).log(Level.SEVERE, "Error writing " + filename, ex);
        } catch (Exception ex) {
            Logger.getLogger(DataSetSaver.class.getName()).log(Level.SEVERE, null, ex);
        }
        
    }
    
}
