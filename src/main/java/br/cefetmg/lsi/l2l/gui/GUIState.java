package br.cefetmg.lsi.l2l.gui;

import br.cefetmg.lsi.l2l.common.ResourceLoader;
import br.cefetmg.lsi.l2l.common.SequentialId;
import br.cefetmg.lsi.l2l.physics.CreatureGeometry;
import br.cefetmg.lsi.l2l.physics.ObjectGeometry;
import org.newdawn.slick.*;
import org.newdawn.slick.geom.Rectangle;
import org.newdawn.slick.geom.ShapeRenderer;
import org.newdawn.slick.state.GameState;
import org.newdawn.slick.state.StateBasedGame;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Created by felipe on 07/01/17.
 */
public class GUIState implements GameState {

    private Map<SequentialId, ObjectGeometry> objects;
    private Map<SequentialId, CreatureGeometry> creatures;

    private Image background;

    public GUIState() {
        objects = new ConcurrentHashMap<>();
        creatures = new ConcurrentHashMap<>();
    }

    public void updateCreature(CreatureGeometry geom) {
        creatures.put(geom.id, geom);
    }

    public void removeCreature(SequentialId id) {
        creatures.remove(id);
    }

    public void addObject(ObjectGeometry geom) {
        objects.put(geom.id, geom);
    }

    public void removeObject(SequentialId id) {
        objects.remove(id);
    }

    @Override
    public int getID() {
        return 0;
    }

    @Override
    public void init(GameContainer gameContainer, StateBasedGame stateBasedGame) throws SlickException {

        ResourceLoader resourceLoader = new ResourceLoader();
        background = resourceLoader.loadImage("background");
    }

    @Override
    public void render(GameContainer gameContainer, StateBasedGame stateBasedGame, Graphics graphics) throws SlickException {

        graphics.drawImage(background, 0, 0);

        Iterator<ObjectGeometry> objectIt = objects.values().iterator();

        while (objectIt.hasNext()) {
            ObjectGeometry geom = objectIt.next();
            geom.load();
            ShapeRenderer.textureFit(geom.shape, geom.getTexture());
        }

        Iterator<CreatureGeometry> creatureIt = creatures.values().iterator();

        while (creatureIt.hasNext()) {
            CreatureGeometry geom = creatureIt.next();
            geom.load();
            ShapeRenderer.textureFit(geom.body, geom.bodyTexture);
            ShapeRenderer.textureFit(geom.mouth, geom.mouthTexture);
            graphics.draw(geom.visionField);
            graphics.draw(geom.olfactoryField);
        }
    }

    @Override
    public void update(GameContainer gameContainer, StateBasedGame stateBasedGame, int i) throws SlickException {

    }

    @Override
    public void enter(GameContainer gameContainer, StateBasedGame stateBasedGame) throws SlickException {

    }

    @Override
    public void leave(GameContainer gameContainer, StateBasedGame stateBasedGame) throws SlickException {

    }

    @Override
    public void controllerLeftPressed(int i) {

    }

    @Override
    public void controllerLeftReleased(int i) {

    }

    @Override
    public void controllerRightPressed(int i) {

    }

    @Override
    public void controllerRightReleased(int i) {

    }

    @Override
    public void controllerUpPressed(int i) {

    }

    @Override
    public void controllerUpReleased(int i) {

    }

    @Override
    public void controllerDownPressed(int i) {

    }

    @Override
    public void controllerDownReleased(int i) {

    }

    @Override
    public void controllerButtonPressed(int i, int i1) {

    }

    @Override
    public void controllerButtonReleased(int i, int i1) {

    }

    @Override
    public void keyPressed(int i, char c) {

    }

    @Override
    public void keyReleased(int i, char c) {

    }

    @Override
    public void mouseWheelMoved(int i) {

    }

    @Override
    public void mouseClicked(int i, int i1, int i2, int i3) {

    }

    @Override
    public void mousePressed(int i, int i1, int i2) {

    }

    @Override
    public void mouseReleased(int i, int i1, int i2) {

    }

    @Override
    public void mouseMoved(int i, int i1, int i2, int i3) {

    }

    @Override
    public void mouseDragged(int i, int i1, int i2, int i3) {

    }

    @Override
    public void setInput(Input input) {

    }

    @Override
    public boolean isAcceptingInput() {
        return false;
    }

    @Override
    public void inputEnded() {

    }

    @Override
    public void inputStarted() {

    }
}
