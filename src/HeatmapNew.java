import java.awt.*;
import java.util.HashMap;
import java.util.Map.Entry;
import java.util.Set;

public class HeatmapNew {
	protected final HashMap<Point, Integer> heatmapHashMap;
	protected static final long heatmapVersion = 100L;
	protected int stepCount;
	protected int[] maxVal = {1, 0, 0}, minVal = {1, 0, 0}; // {val, x, y}
	protected static final int
		HEATMAP_WIDTH = 2752,       //never change these
		HEATMAP_HEIGHT = 1664,      //never change these
		HEATMAP_OFFSET_X = -1152,   //never change these
		HEATMAP_OFFSET_Y = -2496;   //never change these (for backwards compatibility)
	public long playerID = -1;

	public HeatmapNew() {
		this.stepCount = 0;
		this.heatmapHashMap = new HashMap<>();
	}

	public HeatmapNew(long playerID) {
		this.stepCount = 0;
		this.heatmapHashMap = new HashMap<>();
		this.playerID = playerID;
	}

	// The following horse shit is for backwards compatibility with the old, retarded method of storing heatmap data
	public static HeatmapNew convertOldHeatmapToNew(Heatmap oldStyle, long userId) {
		HeatmapNew newStyle = new HeatmapNew(userId);
		for (int x = 0; x < HEATMAP_WIDTH; x++) {
			for (int y = 0; y < HEATMAP_HEIGHT; y++) {
				if (oldStyle.heatmapCoordsGet(x, y) != 0) {
					newStyle.set(x - HEATMAP_OFFSET_X, y - HEATMAP_OFFSET_Y, oldStyle.heatmapCoordsGet(x, y));
				}
			}
		}
		return newStyle;
	}

	//Overloaded ting
	public static HeatmapNew convertOldHeatmapToNew(Heatmap oldStyle) {
		return convertOldHeatmapToNew(oldStyle, -1);
	}

	protected Set<Entry<Point, Integer>> getEntrySet() {
		return heatmapHashMap.entrySet();
	}

	/**
	 * Increments the heatmap's value at the given location by the amount specified
	 *
	 * @param x      Original RuneScape x-coord
	 * @param y      Original RuneScape y-coord
	 * @param amount Amount to increment the value by
	 */
	protected void increment(int x, int y, int amount) {
		int newValue = heatmapHashMap.getOrDefault(new Point(x, y), 0) + amount;
		heatmapHashMap.put(new Point(x, y), newValue);
		stepCount += amount;
		//Update maxval
		if (newValue >= maxVal[0]) {
			maxVal = new int[]{newValue, x, y};
		}
	}

	/**
	 * Increments the heatmap's value at the given location by 1
	 *
	 * @param x Original RuneScape x-coord
	 * @param y Original RuneScape y-coord
	 */
	protected void increment(int x, int y) {
		increment(x, y, 1);
	}

	/**
	 * Sets the heatmap's value at the given location to the given value.
	 *
	 * @param newValue New value
	 * @param x        Original RuneScape x-coord
	 * @param y        Original RuneScape y-coord
	 */
	protected void set(int x, int y, int newValue) {
		//We don't keep track of unstepped-on tiles
		if (newValue < 0) {
			return;
		}

		//Set it & retrieve previous value
		Integer oldValue = heatmapHashMap.put(new Point(x, y), newValue);

		//Update step count
		if (oldValue == null) {
			stepCount += newValue;
		} else {
			stepCount += (newValue - oldValue);
		}

		//Error checking for not keeping track of unstepped-on tiles
		if (newValue == 0) {
			heatmapHashMap.remove(new Point(x, y));
			// If the removed tile was the most stepped on, then we have
			// no choice but to recalculate the new most stepped on tile
			if (newValue == maxVal[0]) {
				int[] newMax = {1, 0, 0};
				for (Entry<Point, Integer> e : heatmapHashMap.entrySet()) {
					if (newMax[0] >= e.getValue()) {
						newMax[0] = e.getValue();
						newMax[1] = e.getKey().x;
						newMax[2] = e.getKey().y;
					}
				}
				maxVal = newMax;
			}
			// It's super unlikely that a removed tile will have been the least
			// stepped on, so I'm just not even gon bother writing error checking for it
			return;
		}

		//Update min/max vals
		if (newValue > maxVal[0]) {
			maxVal = new int[]{newValue, x, y};
		}
		if (newValue <= minVal[0]) {
			minVal = new int[]{newValue, x, y};
		}
	}

	/**
	 * Sets the value without any error checking for step count, min/max values, zero-step tiles, etc
	 *
	 * @param x
	 * @param y
	 * @param newValue
	 */
	protected void setFast(int x, int y, int newValue) {
		heatmapHashMap.put(new Point(x, y), newValue);
	}

	/**
	 * Returns the heatmap's value at the given game world location
	 *
	 * @param x Heatmap-style x-coord
	 * @param y Heatmap-style y-coord
	 */
	protected int get(int x, int y) {
		return heatmapHashMap.get(new Point(x, y));
	}

	protected int getNumTilesVisited() {
		return heatmapHashMap.size();
	}

	protected int getStepCount() {
		return stepCount;
	}

	/**
	 * @return int array holding {maxVal, maxX, maxY} where the latter two are the coordinate at which the max value exists
	 */
	protected int[] getMaxVal() {
		return maxVal;
	}

	/**
	 * @return int array holding {minVal, minX, minY} where the latter two are the coordinate at which the minimum NON-ZERO value exists
	 */
	protected int[] getMinVal() {
		return minVal;
	}
}