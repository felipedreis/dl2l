package br.cefetmg.lsi.l2l.cluster;

import akka.cluster.Member;

/**
 * Created by felipe on 28/08/17.
 */
public interface Registrable {
    void handleNewMember(Member member);
}
