package com.github.joonasvali.naturalmouse.api;

import com.github.joonasvali.naturalmouse.util.Pair;

/**
 * Use to observe mouse movement in MouseMotion
 */
public interface MouseMotionObserver {
  Pair<Integer, Integer> observe(int xPos, int yPos);
}
