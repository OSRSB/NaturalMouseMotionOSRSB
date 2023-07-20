package com.github.joonasvali.naturalmouse.api;

import com.github.joonasvali.naturalmouse.support.DoublePoint;
import com.github.joonasvali.naturalmouse.support.Flow;
import com.github.joonasvali.naturalmouse.support.MouseMotionNature;
import com.github.joonasvali.naturalmouse.support.mousemotion.Movement;
import com.github.joonasvali.naturalmouse.support.mousemotion.MovementFactory;
import com.github.joonasvali.naturalmouse.util.MathUtil;
import com.github.joonasvali.naturalmouse.util.Pair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.awt.*;
import java.util.Random;

/**
 * Contains instructions to move cursor smoothly to the destination coordinates from where ever the cursor
 * currently is. The class is reusable, meaning user can keep calling it and the cursor returns in a random,
 * but reliable way, described in this class, to the destination.
 */
public class MouseMotion {
  private static final Logger log = LoggerFactory.getLogger(MouseMotion.class);
  private static final int SLEEP_AFTER_ADJUSTMENT_MS = 2;
  private final int minSteps;
  private final int effectFadeSteps;
  private final int reactionTimeBaseMs;
  private final int reactionTimeVariationMs;
  private final double timeToStepsDivider;
  private final Dimension screenSize;
  private final SystemCalls systemCalls;
  private final DeviationProvider deviationProvider;
  private final NoiseProvider noiseProvider;
  private final SpeedManager speedManager;
  private final OvershootManager overshootManager;
  private int xDest;
  private int yDest;
  private final Random random;
  private final MouseInfoAccessor mouseInfo;
  private Point mousePosition;

  /**
   * @param nature the nature that defines how mouse is moved
   * @param xDest  the x-coordinate of destination
   * @param yDest  the y-coordinate of destination
   * @param random the random used for unpredictability
   */
  public MouseMotion(MouseMotionNature nature, Random random, int xDest, int yDest) {
    this.deviationProvider = nature.getDeviationProvider();
    this.noiseProvider = nature.getNoiseProvider();
    this.systemCalls = nature.getSystemCalls();
    this.screenSize = systemCalls.getScreenSize();
    this.xDest = limitByScreenWidth(xDest);
    this.yDest = limitByScreenHeight(yDest);
    this.random = random;
    this.mouseInfo = nature.getMouseInfo();
    this.speedManager = nature.getSpeedManager();
    this.timeToStepsDivider = nature.getTimeToStepsDivider();
    this.minSteps = nature.getMinSteps();
    this.effectFadeSteps = nature.getEffectFadeSteps();
    this.reactionTimeBaseMs = nature.getReactionTimeBaseMs();
    this.reactionTimeVariationMs = nature.getReactionTimeVariationMs();
    this.overshootManager = nature.getOvershootManager();
  }

  /**
   * Blocking call, starts to move the cursor to the specified location from where it currently is.
   *
   * @throws InterruptedException when interrupted
   */
  public void move() throws InterruptedException {
    move((x, y) -> {
    return null;
    });
  }

  /**
   * Blocking call, starts to move the cursor to the specified location from where it currently is.
   *
   * @param observer Provide observer if you are interested receiving the location of mouse on every step
   * @throws InterruptedException when interrupted
   */
  public void move(MouseMotionObserver observer) throws InterruptedException {
    updateMouseInfo();
    log.info("Starting to move mouse to ({}, {}), current position: ({}, {})", xDest, yDest, mousePosition.x, mousePosition.y);

    MovementFactory movementFactory = new MovementFactory(xDest, yDest, speedManager, overshootManager, screenSize);
    ArrayDeque<Movement> movements = movementFactory.createMovements(mousePosition);
    int overshoots = movements.size() - 1;

    int sumXDelta = 0;
    int sumYDelta = 0;
    while (mousePosition.x != xDest || mousePosition.y != yDest) {
      if (movements.isEmpty()) {
        // This shouldn't usually happen, but it's possible that somehow we won't end up on the target,
        // Then just re-attempt from mouse new position. (There are known JDK bugs, that can cause sending the cursor
        // to wrong pixel)
        updateMouseInfo();
        log.warn("Re-populating movement array. Did not end up on target pixel.");
        movementFactory = new MovementFactory(xDest, yDest, speedManager, overshootManager, screenSize);
        movements = movementFactory.createMovements(mousePosition);
        overshoots = movements.size() - 1;
        sumXDelta = 0;
        sumYDelta = 0;
      }

      Movement movement = movements.removeFirst();
      if (!movements.isEmpty()) {
        log.debug("Using overshoots ({} out of {}), aiming at ({}, {})",
            overshoots - movements.size() + 1, overshoots, movement.destX, movement.destY);
      }

      double distance = movement.distance;
      long mouseMovementMs = movement.time;
      Flow flow = movement.flow;
      double xDistance = movement.xDistance;
      double yDistance = movement.yDistance;
      double xRelativeDistance = movement.xDistance;
      double yRelativeDistance = movement.yDistance;
      log.debug("Movement arc length computed to {} and time predicted to {} ms", distance, mouseMovementMs);

      /* Number of steps is calculated from the movement time and limited by minimal amount of steps
         (should have at least MIN_STEPS) and distance (shouldn't have more steps than pixels travelled) */
      int steps = (int) Math.ceil(Math.min(distance, Math.max(mouseMovementMs / timeToStepsDivider, minSteps)));

      long startTime = systemCalls.currentTimeMillis();
      long stepTime = (long) (mouseMovementMs / (double) steps);

      updateMouseInfo();
      double simulatedMouseX = mousePosition.x;
      double simulatedMouseY = mousePosition.y;
      int mousePosX = mousePosition.x;
      int mousePosY = mousePosition.y;

      double deviationMultiplierX = (random.nextDouble() - 0.5) * 2;
      double deviationMultiplierY = (random.nextDouble() - 0.5) * 2;

      double completedXDistance = 0;
      double completedYDistance = 0;
      double noiseX = 0;
      double noiseY = 0;

      movement = movementFactory.offsetMovement(movement, new Point(mousePosX, mousePosY), sumXDelta, sumYDelta);
      for (int i = 0; i < steps; i++) {
        // Allow other action to take place or just observe, we'll later compensate by sleeping less.
        if (observer != null) {
          Pair<Integer, Integer> newCoords = observer.observe(mousePosX, mousePosY);
          if (newCoords != null) {
            int xDelta = newCoords.x - xDest;
            int yDelta = newCoords.y - yDest;
            movement = movementFactory.offsetMovement(movement, new Point(mousePosX, mousePosY), xDelta, yDelta);
            sumXDelta += xDelta;
            sumYDelta += yDelta;
            xDest = newCoords.x;
            yDest = newCoords.y;
          }
          flow.renormalizeBuckets(i);
        }

        long endTime = startTime + stepTime * (i + 1);
        long timeLeft = endTime - systemCalls.currentTimeMillis();
        sleepAround(Math.max(timeLeft, 0), 0);

        // distance completed plus the distance to new destination
        distance = movement.distance;
        xRelativeDistance = movement.destX - mousePosX;
        yRelativeDistance = movement.destY - mousePosY;

        // All steps take equal amount of time. This is a value from 0...1 describing how far along the process is.
        double timeCompletion = i / (double) steps;

        double effectFadeStep = Math.max(i - (steps - effectFadeSteps) + 1, 0);
        // value from 0 to 1, when effectFadeSteps remaining steps, starts to decrease to 0 linearly
        // This is here so noise and deviation wouldn't add offset to mouse final position, when we need accuracy.
        double effectFadeMultiplier = (effectFadeSteps - effectFadeStep) / effectFadeSteps;

        double xStepSize;
        double yStepSize;

        if (observer != null ) {
          xStepSize = flow.getStepSizeRelative(xRelativeDistance, steps, timeCompletion);
          yStepSize = flow.getStepSizeRelative(yRelativeDistance, steps, timeCompletion);
        }
        else {
          xStepSize = flow.getStepSize(xDistance, steps, timeCompletion);
          yStepSize = flow.getStepSize(yDistance, steps, timeCompletion);
        }

        completedXDistance += xStepSize;
        completedYDistance += yStepSize;
        double completedDistance = Math.hypot(completedXDistance, completedYDistance);
        double completion = Math.min(1, completedDistance / distance);
        log.trace("Step: x: {} y: {} tc: {} c: {}", xStepSize, yStepSize, timeCompletion, completion);

        DoublePoint noise = noiseProvider.getNoise(random, xStepSize, yStepSize);
        DoublePoint deviation = deviationProvider.getDeviation(distance, completion);

        noiseX += noise.getX();
        noiseY += noise.getY();
        simulatedMouseX += xStepSize;
        simulatedMouseY += yStepSize;

        log.trace("EffectFadeMultiplier: {}", effectFadeMultiplier);
        log.trace("SimulatedMouse: [{}, {}]", simulatedMouseX, simulatedMouseY);

        mousePosX = MathUtil.roundTowards(
            simulatedMouseX +
            deviation.getX() * deviationMultiplierX * effectFadeMultiplier +
            noiseX * effectFadeMultiplier,
            movement.destX
        );

        mousePosY = MathUtil.roundTowards(
            simulatedMouseY +
            deviation.getY() * deviationMultiplierY * effectFadeMultiplier +
            noiseY * effectFadeMultiplier,
            movement.destY
        );

        mousePosX = limitByScreenWidth(mousePosX);
        mousePosY = limitByScreenHeight(mousePosY);

        systemCalls.setMousePosition(
            mousePosX,
            mousePosY
        );

        if (mousePosX == movement.destX && mousePosY == yDest) {
          if (timeLeft > timeToStepsDivider) {
            log.info("Mouse reached destination faster than expected by {} ms.", timeLeft - timeToStepsDivider);
          }
          break;
        }
      }
      updateMouseInfo();

      if (mousePosX != movement.destX || mousePosY != movement.destY) {
        String mouseHopMessage = "Mouse off from step endpoint (adjustment was done) " +
                "x: (" + mousePosX + " -> " + movement.destX + ") " +
                "y: (" + mousePosY + " -> " + movement.destY + ") ";
        if (Math.hypot(mousePosX - movement.destX, mousePosY - movement.destY) > Math.max(3, 2 * Math.hypot(xDistance, yDistance) / steps)) {
          // It's possible that mouse is manually moved or for some other reason.
          // Let's start next step from pre-calculated location to prevent errors from accumulating.
          // But print warning as this is not expected behavior.
          log.warn(mouseHopMessage);
        }
        log.debug(mouseHopMessage);
        systemCalls.setMousePosition(movement.destX, movement.destY);
        // Let's wait a bit before getting mouse info.
        sleepAround(SLEEP_AFTER_ADJUSTMENT_MS, 0);
        updateMouseInfo();
      }

      if (mousePosition.x != xDest || mousePosition.y != yDest) {
        // We are dealing with overshoot, let's sleep a bit to simulate human reaction time.
        sleepAround(reactionTimeBaseMs, reactionTimeVariationMs);
      }
      log.debug("Steps completed, mouse at " + mousePosition.x + " " + mousePosition.y);
    }
    log.info("Mouse movement to ({}, {}) completed", xDest, yDest);
  }

  private int limitByScreenWidth(int value) {
    return Math.max(0, Math.min(screenSize.width - 1, value));
  }

  private int limitByScreenHeight(int value) {
    return Math.max(0, Math.min(screenSize.height - 1, value));
  }

  private void sleepAround(long sleepMin, long randomPart) throws InterruptedException {
    long sleepTime = (long) (sleepMin + random.nextDouble() * randomPart);
    if (log.isTraceEnabled() && sleepTime > 0) {
      updateMouseInfo();
      log.trace("Sleeping at ({}, {}) for {} ms", mousePosition.x, mousePosition.y, sleepTime);
    }
    systemCalls.sleep(sleepTime);
  }

  private void updateMouseInfo() {
    mousePosition = mouseInfo.getMousePosition();
  }

}
