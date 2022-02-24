package br.cefetmg.lsi.l2l.cluster;

import akka.actor.Cancellable;
import akka.actor.UntypedActor;
import br.cefetmg.lsi.l2l.cluster.Finish;
import br.cefetmg.lsi.l2l.common.SequentialId;
import br.cefetmg.lsi.l2l.gui.GUIState;
import br.cefetmg.lsi.l2l.physics.CreatureGeometry;
import br.cefetmg.lsi.l2l.physics.ObjectGeometry;
import org.newdawn.slick.AppGameContainer;
import org.newdawn.slick.GameContainer;
import org.newdawn.slick.SlickException;
import org.newdawn.slick.state.StateBasedGame;
import scala.concurrent.duration.Duration;

import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * Created by felipe on 30/01/17.
 */
public class GUIActor extends UntypedActor {

    private GUIState state;

    private AppGameContainer container;

    private Logger logger;

    public GUIActor () {
        state = new GUIState();
        logger = Logger.getLogger(GUIActor.class.getName());
    }

    @Override
    public void postStop() throws Exception {
        super.postStop();
        container.exit();
        logger.info("GUI stopped");
    }

    @Override
    public void preStart() {
        context().system().scheduler().scheduleOnce(Duration.create(0, TimeUnit.SECONDS), () -> {

            try {
                container = new AppGameContainer(new StateBasedGame("l2l") {
                    @Override
                    public void initStatesList(GameContainer gameContainer) throws SlickException {
                        addState(state);
                    }
                });
                container.setDisplayMode(800, 600, false);
                container.setVSync(true);
                container.setAlwaysRender(true);
                container.start();

            } catch (SlickException ex) {
                ex.printStackTrace();
            }

        }, context().system().dispatcher());
    }

    @Override
    public void onReceive(Object message) {

        // as I don't know the entity's owner of this id, I try both. Hope this doesn't fuck me up
        if (message instanceof SequentialId) {
            state.removeCreature((SequentialId) message);
            state.removeObject((SequentialId) message);

        } else if (message instanceof CreatureGeometry) {
            state.updateCreature((CreatureGeometry) message);

        } else if (message instanceof ObjectGeometry) {
            state.addObject((ObjectGeometry) message);
        } else if (message instanceof Finish){
            logger.info("Got stop order. Destroying GUI container");
            container.destroy();
            context().stop(self());
        } else
            unhandled(message);
    }
}
