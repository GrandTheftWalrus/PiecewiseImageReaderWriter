import java.awt.Color;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.Transparency;
import java.awt.color.ColorSpace;
import java.awt.image.ColorModel;
import java.awt.image.ComponentColorModel;
import java.awt.image.ComponentSampleModel;
import java.awt.image.DataBuffer;
import java.awt.image.Raster;
import java.awt.image.RenderedImage;
import java.awt.image.SampleModel;
import java.awt.image.WritableRaster;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.ObjectInputStream;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.Map.Entry;
import java.util.Vector;
import javax.imageio.IIOImage;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.zip.InflaterInputStream;
import java.util.zip.ZipInputStream;

public class PiecewiseImageReaderWriter
{
	private static final int HEATMAP_WIDTH = 2752;
	private static final int HEATMAP_HEIGHT = 1664;
	private static final int HEATMAP_OFFSET_X = -1152; // never change these
	private static final int HEATMAP_OFFSET_Y = -2496; // never change these
	private static final int HEATMAP_SENSITIVITY = 4;
	private static final double HEATMAP_TRANSPARENCY = 0.65;
	private static int currentTileIndex = 0;
	// An array that holds the (encoded) heatmap coordinates along
	// with their values, sorted by coordinate left-to-right top-to-bottom
	private static LinkedList<Entry<Point, Integer>> sortedHeatmapTiles;
	static HeatmapNew heatmap = readHeatmapFile("heatmap.heatmap");
	private static boolean processingParametersInitialized = false;

	public static void main(String[] args) throws IOException, ClassNotFoundException
	{
		if (args.length != 2)
		{
			System.err.println("Expected two arguments (inputImage and outputImage)");
			System.exit(-1);
		}
		String inputImageName = args[0];
		String outputImageName = args[1];
		// I made it so that the input image is a program resource because
		// the point of this program is to test something I want to do to a
		// resource image in a different program
		System.out.println("Input image: " + inputImageName);
		System.out.println("Output image: " + outputImageName);
		InputStream inputStream = PiecewiseImageReaderWriter.class.getClassLoader().getResourceAsStream(inputImageName);
		File outputImageFile = new File(outputImageName);
		if (inputStream == null)
		{
			System.err.println("Error: resource \"" + inputImageName + "\" doesn't exist");
			System.exit(-1);
		}

		// Prepare the image reader
		ImageInputStream worldMapImageInputStream = ImageIO.createImageInputStream(inputStream);
		ImageReader reader = ImageIO.getImageReadersByFormatName("PNG").next();
		reader.setInput(worldMapImageInputStream, true);

		// Prepare the image writer
		ImageWriter writer = ImageIO.getImageWritersByFormatName("tif").next();
		FileOutputStream fos = new FileOutputStream(outputImageFile);
		BufferedOutputStream bos = new BufferedOutputStream(fos);
		ImageOutputStream ios = ImageIO.createImageOutputStream(bos);
		writer.setOutput(ios);
		ImageWriteParam writeParam = writer.getDefaultWriteParam();
		writeParam.setTilingMode(ImageWriteParam.MODE_EXPLICIT);
		int tileWidth = 8256;
		int tileHeight = 496;
		writeParam.setTiling(tileWidth, tileHeight, 0, 0);
		final int N = reader.getWidth(0) / tileHeight;

		RenderedImage heatmapImage = new HeatmapImage(heatmap, reader, 1, N);
		writeParam.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
		writeParam.setCompressionType("Deflate");
		writeParam.setCompressionQuality(0);
		writer.write(null, new IIOImage(heatmapImage, null, null), writeParam);
		reader.dispose();
		writer.dispose();
	}

	/**
	 * Assumes that the image will be processed in natural reading order pixel-wise (left-to-right, top-to-bottom) otherwise it won't work.
	 * @param imageRegion
	 * @param region
	 * @param heatmap
	 */
	public static void processImageRegion(BufferedImage imageRegion, Rectangle region, HeatmapNew heatmap)
	{
		// Initialize values
		if (!processingParametersInitialized)
		{
			System.out.println("Initializing phase arrays...");
			initializeProcessingParameters();
			processingParametersInitialized = true;
		}

		// Run them heatmap tiles through the ol' rigamarole
		long startTime = System.nanoTime();
		int numTilesProcessed = 0;
		try
		{
			int currRGB = 0;
			double currHue = 0; // a number on the interval [0, 1] that will represent the intensity of the current heatmap pixel
			double minHue = 1 / 3., maxHue = 0;
			double nthRoot = 1 + (HEATMAP_SENSITIVITY - 1.0) / 2;
			int LOG_BASE = 4;

			// For each pixel in current image region
			while (!sortedHeatmapTiles.isEmpty())
			{
				Entry<Point, Integer> tile = sortedHeatmapTiles.poll();
				numTilesProcessed++;
				Point coords = tile.getKey();
				coords = gameCoordsToImageCoords(coords);
				boolean isInImageBounds = (coords.x > 0 && coords.y > 0);
				int x = coords.x;
				int y = coords.y;
				int tileValue = tile.getValue();

				int comparison1 = compareNaturalReadingOrder(x, y, region.x, region.y);
				int comparison2 = compareNaturalReadingOrder(x, y, region.x + region.width, region.y + region.height);
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

				// Calculate normalized step value
				currHue = (float) ((Math.log(tileValue) / Math.log(LOG_BASE)) / (Math.log(heatmap.getMaxVal()[0] + 1 - heatmap.getMinVal()[0]) / Math.log(LOG_BASE)));
				currHue = Math.pow(currHue, 1.0 / nthRoot);
				currHue = (float) (minHue + (currHue * (maxHue - minHue))); // Assign a hue based on normalized step value (values [0, 1] are mapped linearly to hues of [0, 0.333] aka green then yellow, then red)

				// Reassign the new RGB values to the corresponding 9 pixels (we scale by a factor of 3)
				for (int x_offset = 0; x_offset <= 2; x_offset++)
				{
					for (int y_offset = 0; y_offset <= 2; y_offset++)
					{
						int curX = x - region.x + x_offset;
						int curY = y - region.y + y_offset;
						if (curX >= imageRegion.getWidth() || curY >= imageRegion.getHeight())
						{
							continue;
						}
						int srcRGB = imageRegion.getRGB(curX, curY);
						int r = (srcRGB >> 16) & 0xFF;
						int g = (srcRGB >> 8) & 0xFF;
						int b = (srcRGB) & 0xFF;
						double srcBrightness = Color.RGBtoHSB(r, g, b, null)[2] * (1 - HEATMAP_TRANSPARENCY) + HEATMAP_TRANSPARENCY;
						// convert HSB to RGB with the calculated Hue, with Saturation=1 and Brightness according to original map pixel
						currRGB = Color.HSBtoRGB((float) currHue, 1, (float) srcBrightness);
						imageRegion.setRGB(curX, curY, currRGB);
					}
				}
			}
			System.out.printf("Finished processing image chunk %(4d, %4d, %4d, %4d) after %3d ms (%4d) tiles\n", region.x, region.y, region.width, region.height, (System.nanoTime() - startTime) / 1_000_000, numTilesProcessed);
		}
		catch (OutOfMemoryError e)
		{
			e.printStackTrace();
			System.err.println("OutOfMemoryError thrown whilst creating and/or writing image file." + "Perhaps try making a Runelite plugin profile with no other plugins enabled" + "besides World Heatmap. If it works then, then you might have too many plugins" + "running. If not, then I unno chief, perhaps you should submit an issue on the" + "GitHub.");
		}
		catch (Exception e)
		{
			e.printStackTrace();
			System.err.println("Exception thrown whilst processing image region.");
		}
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

	private static void initializeProcessingParameters()
	{
		// Create sorted heatmap tiles array (sorted left-to-right top-to-bottom)
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

	// Loads heatmap from local storage. If file does not exist, or an error occurs, it will return null.
	private static HeatmapNew readHeatmapFile(String filepath)
	{
		System.out.println("Loading heatmap file '" + filepath + "'");
		File heatmapFile = new File(filepath);
		if (!heatmapFile.exists())
		{
			// Return new blank heatmap if specified file doesn't exist
			System.err.println("World Heatmap was not able to load Worldheatmap file " + filepath + " because it does not exist.");
			return null;
		}
		// Detect whether the .heatmap file is the old style (serialized Heatmap, rather than a zipped .CSV file)
		// And if it is, then convert it to the new style
		try (FileInputStream fis = new FileInputStream(filepath))
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
		catch (Exception e)
		{
			// If reached here, then the file was not of the older type.
		}
		// Test if it is of the newer type, or something else altogether.
		try (FileInputStream fis = new FileInputStream(filepath))
		{
			ZipInputStream zis = new ZipInputStream(fis);
			InputStreamReader isr = new InputStreamReader(zis, StandardCharsets.UTF_8);
			BufferedReader reader = new BufferedReader(isr);
			zis.getNextEntry();
			String[] fieldNames = reader.readLine().split(",");
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
					heatmapNew.setFast(Short.parseShort(tile[0]), Short.parseShort(tile[1]), Integer.parseInt(tile[2]));
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
		catch (Exception e)
		{
			e.printStackTrace();
			System.err.println("The file " + filepath + " is not a heatmap file or it is corrupt, or something.");
		}
		return null;
	}

	/**
	 * Class which calculates osrs heatmap image data on demand TODO: Maybe I can make it simpler by just overriding the getData method of BufferedImage or sum?
	 */
	private static class HeatmapImage implements RenderedImage
	{
		private final HeatmapNew heatmap;
		private ImageReader worldMapImageReader = ImageIO.getImageReadersByFormatName("png").next();
		private final int numXTiles;
		private final ColorModel colorModel = new ComponentColorModel(ColorSpace.getInstance(ColorSpace.CS_sRGB), false, false, Transparency.OPAQUE, DataBuffer.TYPE_BYTE);
		private final int numYTiles;

		/**
		 * @param heatmap
		 * @param worldMapImageReader osrs_world_map.png (8256 x 4992)
		 * @param numXTiles           Image width must be evenly divisible by numXTiles
		 * @param numYTiles           Image width must be evenly divisible by numYTiles
		 */
		public HeatmapImage(HeatmapNew heatmap, ImageReader worldMapImageReader, int numXTiles, int numYTiles)
		{
			this.heatmap = heatmap;
			this.worldMapImageReader = worldMapImageReader;
			this.numXTiles = numXTiles;
			this.numYTiles = numYTiles;
			try
			{
				if ((worldMapImageReader.getWidth(0) % numXTiles != 0) || (worldMapImageReader.getHeight(0) % numYTiles != 0))
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
				BufferedImage bi = worldMapImageReader.read(0, readParam);
				processImageRegion(bi, rect, heatmap);
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
	}
}
