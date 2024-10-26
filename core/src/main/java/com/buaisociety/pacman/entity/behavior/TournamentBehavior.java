package com.buaisociety.pacman.entity.behavior;

import com.buaisociety.pacman.entity.Direction;
import com.buaisociety.pacman.entity.Entity;
import com.buaisociety.pacman.entity.PacmanEntity;
import com.buaisociety.pacman.maze.Maze;
import com.buaisociety.pacman.maze.Tile;
import com.buaisociety.pacman.maze.TileState;
import com.cjcrafter.neat.compute.Calculator;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.joml.RoundingMode;
import org.joml.Vector2i;
import org.joml.Vector2ic;

import java.util.ArrayList;

public class TournamentBehavior implements Behavior {

    private final Calculator calculator;
    private @Nullable PacmanEntity pacman;

    private int previousScore = 0;
    private int framesSinceScoreUpdate = 0;

    private int scoreModifier = 10;

    private int lastScoreUpdate = 0;
    private int lastScore = 0;

    Direction[] DIRECTIONS = Direction.values();

    private ArrayList<Tile> hotspot = new ArrayList<>();
    private int frameCounter = 0;

    private Tile lastPosition;
    public TournamentBehavior(Calculator calculator) {
        this.calculator = calculator;
    }

    /**
     * Returns the desired direction that the entity should move towards.
     *
     * @param entity the entity to get the direction for
     * @return the desired direction for the entity
     */
    @NotNull
    @Override
    public Direction getDirection(@NotNull Entity entity) {
        // --- DO NOT REMOVE ---
        if (pacman == null) {
            pacman = (PacmanEntity) entity;
        }

        int newScore = pacman.getMaze().getLevelManager().getScore();
        if (previousScore != newScore) {
            previousScore = newScore;
            framesSinceScoreUpdate = 0;
        } else {
            framesSinceScoreUpdate++;
        }

        if (framesSinceScoreUpdate > 60 * 40) {
            pacman.kill();
            framesSinceScoreUpdate = 0;
        }
        // --- END OF DO NOT REMOVE ---

        // TODO: Put all your code for info into the neural network here

        // SPECIAL TRAINING CONDITIONS
        int latestScore = pacman.getMaze().getLevelManager().getScore();
        if (latestScore > lastScore) {
            lastScore = latestScore;
            lastScoreUpdate = 0;
        }

        // Check if the score doesn't change for 20 seconds on a 60 FPS
        if (lastScoreUpdate++ > (60 * 15)){
            pacman.kill();
            return Direction.RIGHT;
        }

        // Set direction a known Pellet location
        Direction pelletDirection = null;
        Vector2ic dimensions = pacman.getMaze().getDimensions();
        boolean foundOne = false;
        for (int y = 0; y < dimensions.y() && !foundOne; y++) {
            for (int x = 0; x < dimensions.x() && !foundOne; x++) {
                Tile tile = pacman.getMaze().getTile(x, y);
                if (tile.getState() == TileState.PELLET || tile.getState() == TileState.POWER_PELLET) {
                    foundOne = true;
                    pelletDirection = getDirection(tile, pacman);
                    break;
                }
            }
        }

        // Get neighboring Tile, then check if it has pellet
        Tile currentPosition = pacman.getMaze().getTile(pacman.getTilePosition());
        Tile upTile = currentPosition.getNeighbor(Direction.UP);
        Tile downTile = currentPosition.getNeighbor(Direction.DOWN);
        Tile leftTile = currentPosition.getNeighbor(Direction.LEFT);
        Tile rightTile = currentPosition.getNeighbor(Direction.RIGHT);

//         Kill when the position is stagnant
//        if (currentPosition == lastPosition) {
//            pacman.kill();
//            return Direction.RIGHT;
//        }else{
//            lastPosition = currentPosition;
//        }

        // END OF SPECIAL TRAINING CONDITIONS

        // We are going to use these directions a lot for different inputs. Get them all once for clarity and brevity
        Direction forward = pacman.getDirection();
        Direction left = pacman.getDirection().left();
        Direction right = pacman.getDirection().right();
        Direction behind = pacman.getDirection().behind();

        // Input nodes 1, 2, 3, and 4 show if the pacman can move in the forward, left, right, and behind directions
        float canMoveForward = pacman.canMove(forward) ? .75f : 0f ;
        float canMoveLeft = pacman.canMove(left)? .75f : 0f;
        float canMoveRight = pacman.canMove(right)? .75f : 0f;
        float canMoveBehind = pacman.canMove(behind)? .75f : 0f;

        // Reward if the direction is going towards a pellet
        switch (pelletDirection){
            case UP -> canMoveForward += .10;
            case DOWN -> canMoveBehind += .10;
            case LEFT -> canMoveLeft += .10;
            case RIGHT -> canMoveRight += .10;
        }

        // Reward if neighboring tile has Pellet
        if (isTileContainsPellet(upTile)){
            canMoveForward += .10;
        }else if (isTileContainsPellet(downTile)){
            canMoveBehind += .10;
        }else if (isTileContainsPellet(leftTile)){
            canMoveLeft += .10;
        }else if (isTileContainsPellet(rightTile)){
            canMoveRight += .10;
        }

        // Punish if the next tile is a hotspot
        frameCounter++;
        if (frameCounter % 100 == 0){
            hotspot.add(currentPosition);
        }
        if (hotspot.contains(upTile)){
            canMoveForward -= .35;
        }else if (hotspot.contains(downTile)){
            canMoveBehind -= .35;
        }else if (hotspot.contains(leftTile)){
            canMoveLeft -= .35;
        }else if (hotspot.contains(rightTile)){
            canMoveRight -= .35;
        }

        float[] inputs = new float[] {
            canMoveForward,
            canMoveLeft,
            canMoveRight,
            canMoveBehind,
//            ThreadLocalRandom.current().nextFloat(),
            (pelletDirection == null) ? direction2float(pelletDirection) : 0f ,
        };
        float[] outputs = calculator.calculate(inputs).join();

        // Chooses the maximum output as the direction to go... feel free to change this ofc!
        // Adjust this to whatever you used in the NeatPacmanBehavior.class
        int index = 0;
        float max = outputs[0];
        for (int i = 1; i < outputs.length; i++) {
            if (outputs[i] > max) {
                max = outputs[i];
                index = i;
            }
        }

        return switch (index) {
            case 0 -> pacman.getDirection();
            case 1 -> pacman.getDirection().left();
            case 2 -> pacman.getDirection().right();
            case 3 -> pacman.getDirection().behind();
            default -> throw new IllegalStateException("Unexpected value: " + index);
        };
    }


    private Direction getDirection(Tile tile,Entity entity) {
        Vector2i target = new Vector2i(tile.getPosition().x(), tile.getPosition().y());
        Direction temp = null;
        int smallest = Integer.MAX_VALUE;

        Tile current = tile;
        for (Direction direction : DIRECTIONS) {

            Tile next = current.getNeighbor(direction);
            if (!next.getState().isPassable())
                continue;

            Vector2i location = new Vector2i(entity.getPosition().div(Maze.TILE_SIZE), RoundingMode.TRUNCATE).add(direction.asVector());
            int distance = (int) location.distanceSquared(target);

            if (distance <= smallest) {
                smallest = distance;
                temp = direction;
            }
        }

        if (temp == null)
            return entity.getDirection();

        return temp;
    }

    private float direction2float (Direction direction){
        float f;
        switch(direction) {
            case Direction.UP:
                f = 1.0f;
                break;
            case Direction.DOWN:
                f= 2.0f;
                break;
            case Direction.LEFT:
                f = 3.0f;
                break;
            case Direction.RIGHT:
                f = 4.0f;
                break;
            default:
                return 0.0f;
        }
        return f;
    }

    private boolean isTileContainsPellet(Tile tile){
        return tile.getState() == TileState.PELLET || tile.getState() == TileState.POWER_PELLET;
    }
}
