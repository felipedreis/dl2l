package br.cefetmg.lsi.l2l.stimuli;

import br.cefetmg.lsi.l2l.common.SequentialId;

import java.io.Serializable;

/**
 * This class abstracts a message exchanged between two components. Those components may be of creature internal system
 * or a world component, as a fruit, a bee, etc.
 * A stimuli generally has an author, or a origin. After being created, it may be passed through the creature nervous system,
 * trigger a behaviour and finally a response. This origin is important to identify the component that will receive a response,
 * like a destruction command, a eat command, or a mechanical command.
 *
 * @author Felipe Duarte dos Reis [felipeduarte@lsi.cefetmg.br]
 *
 * Created by felipe on 02/01/17.
 */
public abstract class Stimulus implements Serializable{

    public final SequentialId origin;

    public final SequentialId stimulusId;

    public Stimulus(SequentialId origin, SequentialId stimulusId) {
        this.origin = origin;
        this.stimulusId = stimulusId;
    }

}
