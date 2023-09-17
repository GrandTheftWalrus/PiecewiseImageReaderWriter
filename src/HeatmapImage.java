import java.awt.Color;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.Transparency;
import java.awt.color.ColorSpace;
import java.awt.image.BufferedImage;
import java.awt.image.ColorModel;
import java.awt.image.ComponentColorModel;
import java.awt.image.ComponentSampleModel;
import java.awt.image.DataBuffer;
import java.awt.image.Raster;
import java.awt.image.RenderedImage;
import java.awt.image.SampleModel;
import java.awt.image.WritableRaster;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.ObjectInputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedList;
import java.util.Map;
import java.util.Vector;
import java.util.zip.InflaterInputStream;
import java.util.zip.ZipInputStream;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;

/**
 * Class which calculates osrs heatmap image data on demand
 */
public class HeatmapImage implements RenderedImage
{
	private final ImageReader worldMapImageReader;
	private final ColorModel colorModel = new ComponentColorModel(ColorSpace.getInstance(ColorSpace.CS_sRGB), false, false, Transparency.OPAQUE, DataBuffer.TYPE_BYTE);

	// A queue that holds the heatmap coordinates along
	// with their values, to be sorted by coordinate left-to-right top-to-bottom
	private static LinkedList<Map.Entry<Point, Integer>> sortedHeatmapTiles;
	private final int numXTiles = 1;
	private final int numYTiles;
	private int heatmapMinVal;
	private int heatmapMaxVal;

	/**
	 * @param worldMapImageReader osrs_world_map.png (8256 x 4992)
	 * @param numYTiles Image width must be evenly divisible by numYTiles
	 */
	public HeatmapImage(File heatmapFile, ImageReader worldMapImageReader, int numYTiles)
	{
		initializeProcessingVariables(readHeatmapFile(heatmapFile));
		this.worldMapImageReader = worldMapImageReader;
		this.numYTiles = numYTiles;
		try
		{
			worldMapImageReader.getWidth(0);
			if (worldMapImageReader.getHeight(0) % numYTiles != 0)
			{
				throw new IllegalArgumentException();
			}
		}
		catch (IOException e)
		{
			throw new RuntimeException(e);
		}
	}

	@Override
	public Vector<RenderedImage> getSources()
	{
		return null;
	}

	@Override
	public Object getProperty(String name)
	{
		return null;
	}

	@Override
	public String[] getPropertyNames()
	{
		return new String[0];
	}

	@Override
	public ColorModel getColorModel()
	{
		return colorModel;
	}

	@Override
	public SampleModel getSampleModel()
	{
		return new ComponentSampleModel(DataBuffer.TYPE_BYTE, getWidth(), getHeight(), 1, 1, new int[]{0, 0, 0});
	}

	@Override
	public int getWidth()
	{
		try
		{
			return worldMapImageReader.getWidth(0);
		}
		catch (IOException e)
		{
			throw new RuntimeException(e);
		}
	}

	@Override
	public int getHeight()
	{
		try
		{
			return worldMapImageReader.getHeight(0);
		}
		catch (IOException e)
		{
			throw new RuntimeException(e);
		}
	}

	@Override
	public int getMinX()
	{
		return 0;
	}

	@Override
	public int getMinY()
	{
		return 0;
	}

	@Override
	public int getNumXTiles()
	{
		return numXTiles;
	}

	@Override
	public int getNumYTiles()
	{
		return numYTiles;
	}

	@Override
	public int getMinTileX()
	{
		return 0;
	}

	@Override
	public int getMinTileY()
	{
		return 0;
	}

	@Override
	public int getTileWidth()
	{
		return getWidth() / numXTiles;
	}

	@Override
	public int getTileHeight()
	{
		return getHeight() / numYTiles;
	}

	@Override
	public int getTileGridXOffset()
	{
		return 0;
	}

	@Override
	public int getTileGridYOffset()
	{
		return 0;
	}

	@Override
	public Raster getTile(int tileX, int tileY)
	{
		int x = tileX * getTileWidth();
		int y = tileY * getTileHeight();
		return getData(new Rectangle(x, y, getTileWidth(), getTileHeight()));
	}

	@Override
	public Raster getData()
	{
		return getData(new Rectangle(0, 0, getWidth(), getHeight()));
	}

	@Override
	public Raster getData(Rectangle rect)
	{
		ImageReadParam readParam = worldMapImageReader.getDefaultReadParam();
		readParam.setSourceRegion(rect);
		try
		{
			// Reads only the specified rect from osrs_world_map.png into memory
			BufferedImage bi = worldMapImageReader.read(0, readParam);
			processImageRegion(bi, rect);
			return bi.getData();
		}
		catch (IOException e)
		{
			throw new RuntimeException(e);
		}
	}

	@Override
	public WritableRaster copyData(WritableRaster raster)
	{
		return null;
	}

	/**
	 * Assumes that the image will be processed in natural reading order pixel-wise (left-to-right, top-to-bottom) otherwise it won't work.
	 * Make sure that initializeProcessingParameters() has been run before running this
	 * @param imageRegion The image region to be drawn on
	 * @param region The x,y,width,height coordinates of where the imageRegion came from in the whole image
	 */
	public void processImageRegion(BufferedImage imageRegion, Rectangle region)
	{
		// Run them heatmap tiles through the ol' rigamarole
		long startTime = System.nanoTime();
		int numTilesProcessed = 0;
		// For each pixel in current image region
		while (!sortedHeatmapTiles.isEmpty())
		{
			Map.Entry<Point, Integer> tile = sortedHeatmapTiles.poll();
			numTilesProcessed++;
			Point coords = tile.getKey();
			coords = gameCoordsToImageCoords(coords);
			boolean isInImageBounds = (coords.x > 0 && coords.y > 0);
			int tileValue = tile.getValue();

			int comparison1 = compareNaturalReadingOrder(coords.x, coords.y, region.x, region.y);
			int comparison2 = compareNaturalReadingOrder(coords.x, coords.y, region.x + region.width, region.y + region.height);
			// If current tile is after bottom right edge of current image region in reading order
			if (comparison2 > 0)
			{
				// put it back in the front of the queue and return
				sortedHeatmapTiles.addFirst(tile);
				break;
			}
			// If current tile is before upper left edge of current image region, or is out of bounds of the overworld, or hasn't been stepped on, skip
			if (comparison1 < 0 || !isInImageBounds || tileValue == 0)
			{
				continue;
			}
			// Else continue

			// Calculate color
			int heatmapSensitivity = 4;
			double currHue = calculateHue(tileValue, heatmapSensitivity, heatmapMinVal, heatmapMaxVal);

			// Reassign the new RGB values to the corresponding 9 pixels (each tile covers 3x3 image pixels)
			for (int x_offset = 0; x_offset <= 2; x_offset++)
			{
				for (int y_offset = 0; y_offset <= 2; y_offset++)
				{
					int curX = coords.x - region.x + x_offset;
					int curY = coords.y - region.y + y_offset;
					if (curX >= imageRegion.getWidth() || curY >= imageRegion.getHeight())
					{
						continue;
					}
					int srcRGB = imageRegion.getRGB(curX, curY);
					int r = (srcRGB >> 16) & 0xFF;
					int g = (srcRGB >> 8) & 0xFF;
					int b = (srcRGB) & 0xFF;
					float HEATMAP_TRANSPARENCY = 0.65f;
					float brightness = Color.RGBtoHSB(r, g, b, null)[2] * (1 - HEATMAP_TRANSPARENCY) + HEATMAP_TRANSPARENCY;
					// convert HSB to RGB with the calculated Hue, with Saturation=1
					int currRGB = Color.HSBtoRGB((float) currHue, 1, brightness);
					imageRegion.setRGB(curX, curY, currRGB);
				}
			}
		}
		System.out.printf("Finished processing image chunk %(4d, %4d, %4d, %4d) after %3d ms (%4d) tiles\n", region.x, region.y, region.width, region.height, (System.nanoTime() - startTime) / 1_000_000, numTilesProcessed);
	}

	private double calculateHue(int tileValue, int heatmapSensitivity, int minVal, int maxVal)
	{
		double nthRoot = 1 + (heatmapSensitivity - 1.0) / 2;
		int logBase = 4;
		double minHue = 1 / 3.0;
		double maxHue = 0.0;
		double currHue = (float) ((Math.log(tileValue) / Math.log(logBase)) / (Math.log(maxVal + 1 - minVal) / Math.log(logBase)));
		currHue = Math.pow(currHue, 1.0 / nthRoot);
		currHue = (float) (minHue + (currHue * (maxHue - minHue))); // Assign a hue based on normalized step value (values [0, 1] are mapped linearly to hues of [0, 0.333] aka green then yellow, then red)
		return currHue;
	}

	private static int compareNaturalReadingOrder(int x1, int y1, int x2, int y2)
	{
		if (y1 < y2)
		{
			return -1;
		}
		if (y1 > y2)
		{
			return 1;
		}
		else
		{
			return x1 - x2;
		}
	}

	private void initializeProcessingVariables(HeatmapNew heatmap)
	{
		// Create sorted heatmap tiles array (sorted left-to-right top-to-bottom)
		heatmapMaxVal = heatmap.getMaxVal()[0];
		heatmapMinVal = heatmap.getMinVal()[0];
		sortedHeatmapTiles = new LinkedList<>(heatmap.getEntrySet());
		sortedHeatmapTiles.sort((tile1, tile2) -> {
			Point coords1 = tile1.getKey();
			Point coords2 = tile2.getKey();
			return compareNaturalReadingOrder(coords1.x, -coords1.y, coords2.x, -coords2.y);
		});
	}

	/**
	 * @param point True gameworld coordinate
	 * @return The upper-left of the 9-pixel square location on the image osrs_world_map.png that this game coordinate responds to (1 game coordinate = 3x3 pixels). If it is out of bounds, then (-1, -1) is returned
	 */
	public static Point gameCoordsToImageCoords(Point point)
	{
		int HEATMAP_WIDTH = 2752;
		int HEATMAP_HEIGHT = 1664;
		int HEATMAP_OFFSET_X = -1152;
		int HEATMAP_OFFSET_Y = -2496;

		Point pixelLocation = new Point(3 * (point.x + HEATMAP_OFFSET_X), 3 * (HEATMAP_HEIGHT - (point.y + HEATMAP_OFFSET_Y) - 1));
		if (pixelLocation.x < 0 || pixelLocation.y < 0 || pixelLocation.x > HEATMAP_WIDTH * 3 || pixelLocation.y > HEATMAP_HEIGHT * 3)
		{
			return new Point(-1, -1);
		}
		else
		{
			return pixelLocation;
		}
	}

	/**
	 * Loads heatmap from local storage. Throws an exception if the file isn't found or couldn't be read
 	 */
	private static HeatmapNew readHeatmapFile(File heatmapFile)
	{
		System.out.println("Loading heatmap file '" + heatmapFile.getName() + "'");
		// Try reading the file as the old style (serialized Heatmap, rather than a zipped .CSV file)
		// And if it is, then convert it to the new style
		try (FileInputStream fis = new FileInputStream(heatmapFile))
		{
			InflaterInputStream iis = new InflaterInputStream(fis);
			ObjectInputStream ois = new ObjectInputStream(iis);
			Object heatmap = ois.readObject();
			if (heatmap instanceof Heatmap)
			{
				System.out.println("Attempting to convert old-style heatmap file to new style...");
				long startTime = System.nanoTime();
				HeatmapNew result = HeatmapNew.convertOldHeatmapToNew((Heatmap) heatmap);
				System.out.println("Finished converting old-style heatmap to new style in " + (System.nanoTime() - startTime) / 1_000_000 + " ms");
				return result;
			}
		}
		catch (FileNotFoundException e)
		{
			System.err.println("World Heatmap was not able to load Worldheatmap file " + heatmapFile.getName() + " because it does not exist.");
			throw new RuntimeException(e);
		}
		catch (ClassCastException e){
			// If reached here, then the file was not of the older type,
			// so we'll continue on to try reading it as the newer type
		}
		catch (Exception e){
			// we'll try it again with the new type and see if it works yolo
		}

		// Try reading it as the newer type
		try (FileInputStream fis = new FileInputStream(heatmapFile))
		{
			ZipInputStream zis = new ZipInputStream(fis);
			InputStreamReader isr = new InputStreamReader(zis, StandardCharsets.UTF_8);
			BufferedReader reader = new BufferedReader(isr);
			zis.getNextEntry();
			reader.readLine(); // Skip past field names
			String[] fieldValues = reader.readLine().split(",");
			long userID = (fieldValues[0].isEmpty() ? -1 : Long.parseLong(fieldValues[0]));
			int stepCount = (fieldValues[0].isEmpty() ? -1 : Integer.parseInt(fieldValues[2]));
			int maxVal = (fieldValues[0].isEmpty() ? -1 : Integer.parseInt(fieldValues[3]));
			int maxValX = (fieldValues[0].isEmpty() ? -1 : Integer.parseInt(fieldValues[4]));
			int maxValY = (fieldValues[0].isEmpty() ? -1 : Integer.parseInt(fieldValues[5]));
			int minVal = (fieldValues[0].isEmpty() ? -1 : Integer.parseInt(fieldValues[6]));
			int minValX = (fieldValues[0].isEmpty() ? -1 : Integer.parseInt(fieldValues[7]));
			int minValY = (fieldValues[0].isEmpty() ? -1 : Integer.parseInt(fieldValues[8]));

			HeatmapNew heatmapNew;
			if (userID != -1)
			{
				heatmapNew = new HeatmapNew(userID);
			}
			else
			{
				heatmapNew = new HeatmapNew();
			}
			heatmapNew.maxVal = new int[]{maxVal, maxValX, maxValY};
			heatmapNew.minVal = new int[]{minVal, minValX, minValY};
			heatmapNew.stepCount = stepCount;

			// Read the tile values
			final int[] errorCount = {0}; // Number of parsing errors occurred during read
			reader.lines().forEach(s -> {
				String[] tile = s.split(",");
				try
				{
					heatmapNew.set(Short.parseShort(tile[0]), Short.parseShort(tile[1]), Integer.parseInt(tile[2]));
				}
				catch (NumberFormatException e)
				{
					errorCount[0]++;
				}
			});
			if (errorCount[0] > 0)
			{
				System.err.println(errorCount[0] + " errors occurred during heatmap file read.");
			}
			return heatmapNew;
		}
		catch (FileNotFoundException e)
		{
			System.err.println("World Heatmap was not able to load Worldheatmap file " + heatmapFile.getName() + " because it does not exist.");
			throw new RuntimeException(e);
		}
		catch (IOException e)
		{
			System.err.println("The file " + heatmapFile.getName() + " is not a heatmap file or it is corrupt, or something.");
			throw new RuntimeException(e);
		}
	}
}