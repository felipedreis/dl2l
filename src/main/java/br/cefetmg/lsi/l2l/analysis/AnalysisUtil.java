package br.cefetmg.lsi.l2l.analysis;

import java.util.Arrays;
import java.util.List;

/**
 * Created by felipe on 11/12/15.
 */
public class AnalysisUtil {

    public static Double[][] mergeOnTime(Double [] time1, Double [] obsv1, Double [] time2, Double [] obsv2) {

        int timeSize = time1.length + time2.length;
        int i, j, k;

        Double [] time = new Double[timeSize];
        Double [] newObsv1 = new Double[timeSize];
        Double [] newObsv2 = new Double[timeSize];

        i = j = k = 0;

        while(k < timeSize) {
            if(i < time1.length && j < time2.length) {
                if (time1[i] < time2[j]) {
                    time[k] = time1[i];
                    newObsv1[k] = obsv1[i++];
                    newObsv2[k] = null;

                } else if (time1[i] > time2[j]) {
                    time[k] = time2[j];
                    newObsv1[k] = null;
                    newObsv2[k] = obsv2[j++];

                } else {
                    time[k] = time1[i];
                    newObsv1[k] = obsv1[i++];
                    newObsv2[k] = obsv2[j++];
                }

            } else if(i < time1.length) {
                time[k] = time1[i];
                newObsv1[k] = obsv1[i++];
                newObsv2[k] = null;

            } else if(j < time2.length) {
                time[k] = time2[j];
                newObsv1[k] = null;
                newObsv2[k] = obsv2[j++];

            } else {
                break;
            }

            k++;
        }

        time = Arrays.copyOf(time, k);
        newObsv1 = Arrays.copyOf(newObsv1, k);
        newObsv2 = Arrays.copyOf(newObsv2, k);

        return new Double[][] {time, newObsv1, newObsv2};
    }

    /**
     *
     * @param values
     * @return
     */
    public static <T extends Enum<?>> String[] legends(T [] values){

        String legends [] = new String[values.length];
        int i = 0;

        for(T t : values) {
            legends[i++] = t.name().toLowerCase().replace('_', ' ');
        }

        return legends;
    }

    /**
     * Create a matrix of m columns and n of accumulated frequencies.
     * @param data
     * @param m number of elements
     * @param n number of classes
     * @return a matrix with n lines (classes) and accumulated frequencies through the columns
     */
    public static <T extends Enum<?>> Integer[][]  accumulatedFrequencies(List<T> data, int m, int n) {

        if(data.isEmpty()) {
            return null;
        }

        Integer[][] accumulated = new Integer[n][m];

        for (int i = 0; i < m; ++i) {
            T type = data.get(i);

            for (int j = 0; j < n; ++j) {

                if (type.ordinal() == j) {
                    accumulated[j][i] = (i == 0) ? 1
                            : accumulated[j][i - 1] + 1;
                } else {
                    accumulated[j][i] = (i == 0) ? 0 : accumulated[j][i - 1];
                }
            }
        }

        return accumulated;
    }

}
