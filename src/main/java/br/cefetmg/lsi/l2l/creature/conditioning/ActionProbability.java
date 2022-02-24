package br.cefetmg.lsi.l2l.creature.conditioning;

import br.cefetmg.lsi.l2l.creature.common.ActionType;

/**
 * Created by felipe on 23/08/17.
 */
public class ActionProbability {

    private ActionType action;

    private double probability;

    public ActionProbability(ActionType action, double probability) {
        this.action = action;
        this.probability = probability;
    }

    public ActionType getAction() {
        return this.action;
    }

    public void setAction(ActionType action) {
        this.action = action;
    }

    public double getProbability() {
        return this.probability;
    }

    public void setProbability(double probability) {
        this.probability = probability < 0? 0 : probability;
    }

    public void varyProbability(double delta) {
        this.probability = this.probability + delta < 0? 0 : this.probability + delta;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        ActionProbability that = (ActionProbability) o;

        if (action != null ? !action.equals(that.action) : that.action != null) return false;

        return true;
    }

    @Override
    public int hashCode() {
        int result;
        long temp;
        result = action != null ? action.hashCode() : 0;
        temp = Double.doubleToLongBits(probability);
        result = 31 * result + (int) (temp ^ (temp >>> 32));
        return result;
    }
}
