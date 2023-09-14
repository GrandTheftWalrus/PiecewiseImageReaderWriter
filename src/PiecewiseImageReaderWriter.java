import java.awt.Color;
import org.eclipse.collections.api.tuple.primitive.IntIntPair;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.zip.InflaterInputStream;
import java.util.zip.ZipInputStream;
import ar.com.hjg.pngj.*;

public class PiecewiseImageReaderWriter
{
	private static final int HEATMAP_WIDTH = 2752;
	private static final int HEATMAP_HEIGHT = 1664;
	private static final int HEATMAP_OFFSET_X = -1152; // never change these
	private static final int HEATMAP_OFFSET_Y = -2496; // never change these
	private static final int HEATMAP_SENSITIVITY = 4;
	private static final int N = 3;
	private static final double HEATMAP_TRANSPARENCY = 0.65;
	private static int currentTileIndex = 0;
	// An array that holds the (encoded) heatmap coordinates along
	// with their values, sorted by coordinate left-to-right top-to-bottom
	private static IntIntPair[] sortedHeatmapTiles;
	static HeatmapNew heatmap;
	private static boolean processingParametersInitialized = false;
	private static int numTilesProcessed = 0;

	public static void main(String[] args) throws IOException
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

		// Load ze heatmap
		heatmap = readHeatmapFile("heatmap.heatmap");

		// Prepare the image reader
		PngReader reader = new PngReaderByte(inputStream);
		ImageInfo imageInfo = reader.imgInfo;

		// Prepare the image writer
		PngWriter writer = new PngWriter(outputImageFile, imageInfo);

		long startTime = System.nanoTime();
		for (int rowNum = 0; reader.hasMoreRows(); rowNum += 3)
		{
			// Read three horizontal stripes of the image
			byte[][] threeLines = new byte[3][];
			threeLines[0] = Arrays.copyOf(((ImageLineByte) reader.readRow()).getScanlineByte(), imageInfo.cols * imageInfo.bytesPixel);
			threeLines[1] = Arrays.copyOf(((ImageLineByte) reader.readRow()).getScanlineByte(), imageInfo.cols * imageInfo.bytesPixel);
			threeLines[2] = ((ImageLineByte) reader.readRow()).getScanlineByte(); // Last one per loop doesn't need to be a copy

			// Edit the image line
			processThreeImageLines(threeLines, 0, rowNum, imageInfo.cols, 3);

			// Write the region to the image in the ImageWriter
			writer.writeRow(new ImageLineByte(imageInfo, threeLines[0]));
			writer.writeRow(new ImageLineByte(imageInfo, threeLines[1]));
			writer.writeRow(new ImageLineByte(imageInfo, threeLines[2]));
		}

		// Close the image reader
		reader.close();
		// Finalize the written image file
		writer.end();
		System.out.println("Finished processing image after " + (System.nanoTime() - startTime) / 1_000_000 + " ms (" + numTilesProcessed + " tiles)");
	}

	public static void processImageLine(ImageLineByte imageLine, int rowNum, int regionWidth)
	{
		int regionX = 0;
		int regionHeight = 1;
		// Initialize values
		if (!processingParametersInitialized)
		{
			System.out.println("Initializing phase arrays...");
			initializeProcessingParameters();
			processingParametersInitialized = true;
		}

		// For each group of three pixels (9 bytes)
		for (int byteNum = 0; byteNum < imageLine.getSize(); byteNum += 9)
		{
			// Find value of game tile corresponding to leftmost pixel (we should be 3-pixel aligned)
			short[] gameCoords = imageCoordsToGameCoords(byteNum / 3, rowNum);
			int tileValue = heatmap.get(gameCoords[0], gameCoords[1]);

			// Change each pixel
			if (tileValue == 0)
			{
				continue;
			}
			for (int pixelNum = 0; pixelNum < 3; pixelNum++)
			{
				byte r = imageLine.getScanlineByte()[byteNum + pixelNum * 3];
				byte g = imageLine.getScanlineByte()[byteNum + pixelNum * 3 + 1];
				byte b = imageLine.getScanlineByte()[byteNum + pixelNum * 3 + 2];
				double srcBrightness = Color.RGBtoHSB(r, g, b, null)[2] * (1 - HEATMAP_TRANSPARENCY) + HEATMAP_TRANSPARENCY;
				// convert HSB to RGB with the calculated Hue, with Saturation=1 and Brightness according to
				// original map pixel
				double currHue = getHue(tileValue);
				int currRGB = Color.HSBtoRGB((float) currHue, 1, (float) srcBrightness);
				imageLine.getScanlineByte()[byteNum + pixelNum * 3] = (byte) ((currRGB >>> 16) & 0xFF);
				imageLine.getScanlineByte()[byteNum + pixelNum * 3 + 1] = (byte) ((currRGB >>> 8) & 0xFF);
				imageLine.getScanlineByte()[byteNum + pixelNum * 3 + 2] = (byte) (currRGB & 0xFF);
			}
		}
	}

	public static void processThreeImageLines(byte[][] imageLines, int regionX, int regionY, int regionWidth, int regionHeight)
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
		try
		{
			// For each pixel in current image region
			while (currentTileIndex < sortedHeatmapTiles.length)
			{
				//Skip through the sorted tiles until you find one in the current region
				IntIntPair tile = sortedHeatmapTiles[currentTileIndex++];
				short[] gameCoords = HeatmapNew.decodeIntCoordinate(tile.getOne());
				short[] imageCoords = gameCoordsToImageCoords(gameCoords[0], gameCoords[1]);
				int tileImageX = imageCoords[0];
				int tileImageY = imageCoords[1];
				int tileValue = tile.getTwo();
				boolean isOutOfImageBounds = (tileImageX == -1 || tileImageY == -1); //they'd be -1 if the tile was out of image bounds
				boolean tilePrecedesRegion = compareNaturalReadingOrder(tileImageX, tileImageY, regionX, regionY) < 0;
				// Ignore this tile if it's somehow out of bounds or is 0
				if (tilePrecedesRegion || isOutOfImageBounds || tileValue == 0)
				{
					continue;
				}

				// If current tile is beyond the current region, return so the next region can be brought in for this tile
				boolean tileIsBeyondRegion = compareNaturalReadingOrder(tileImageX, tileImageY, regionX + regionWidth - 3, regionY + regionHeight - 3) > 0;
				if (tileIsBeyondRegion)
				{
					currentTileIndex--;
					break;
				}
				numTilesProcessed++;

				// Reassign the new RGB values to the corresponding 9 pixels (we scale by a factor of 3)
				for (int x_offset = 0; x_offset <= 2; x_offset++)
				{
					for (int y_offset = 0; y_offset <= 2; y_offset++)
					{
						int curX = tileImageX - regionX + x_offset;
						int curY = tileImageY - regionY + y_offset;
						if (curX >= regionWidth || curY >= regionHeight)
						{
							continue;
						}
						int r = Byte.toUnsignedInt(imageLines[curY][curX * 3]); // the 3* is because the second dimension of this array holds colour bytes, not pixels
						int g = Byte.toUnsignedInt(imageLines[curY][curX * 3 + 1]);
						int b = Byte.toUnsignedInt(imageLines[curY][curX * 3 + 2]);
						double srcBrightness = Color.RGBtoHSB(r, g, b, null)[2] * (1 - HEATMAP_TRANSPARENCY) + HEATMAP_TRANSPARENCY;
						// convert HSB to RGB with the calculated Hue, with Saturation=1 and Brightness according to
						// original map pixel
						double currHue = getHue(tileValue);
						int currRGB = Color.HSBtoRGB((float) currHue, 1, (float) srcBrightness);
						imageLines[curY][curX * 3] = (byte) ((currRGB >>> 16) & 0xFF);
						imageLines[curY][curX * 3 + 1] = (byte) ((currRGB >>> 8) & 0xFF);
						imageLines[curY][curX * 3 + 2] = (byte) (currRGB & 0xFF);
					}
				}
			}
			//System.out.println("Finished processing image scanline after " + (System.nanoTime() - startTime) / 1_000_000 + " ms (" + numTilesProcessed + " tiles)");
		}
		catch (OutOfMemoryError e)
		{
			e.printStackTrace();
			System.err.println("OutOfMemoryError thrown whilst processing image scanline " + regionY + ". Perhaps try " + "making a Runelite plugin profile with no other plugins enabled" + "besides World Heatmap. If it " + "works then, then you might have too many plugins" + "running. If not, then I unno chief, perhaps" + " you should submit an issue on the" + "GitHub.");
		}
		catch (Exception e)
		{
			e.printStackTrace();
			System.err.println("Exception thrown whilst processing image region.");
		}
	}

	private static double getHue(int tileValue)
	{
		double minHue = 1 / 3.0;
		double maxHue = 0;
		double nthRoot = 1 + (HEATMAP_SENSITIVITY - 1.0) / 2;
		int LOG_BASE = 4;
		double normalizedStepValue = (float) ((Math.log(tileValue) / Math.log(LOG_BASE)) / (Math.log(heatmap.getMaxVal()[0] + 1 - heatmap.getMinVal()[0]) / Math.log(LOG_BASE)));
		normalizedStepValue = Math.pow(normalizedStepValue, 1.0 / nthRoot);
		// Step values normalized to [0, 1] are mapped linearly to hues of [0, 0.333] aka green then yellow, then red.
		return (float) (minHue + (normalizedStepValue * (maxHue - minHue)));
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
		// First, to normalize the range of step values, we find the maximum and minimum values in the heatmap
		int[] maxValAndCoords = heatmap.getMaxVal();
		int maxVal = maxValAndCoords[0];
		int maxX = maxValAndCoords[1];
		int maxY = maxValAndCoords[2];
		System.out.println("Maximum steps on a tile is: " + maxVal + " at (" + maxX + ", " + maxY + ")");
		int[] minValAndCoords = heatmap.getMinVal();
		int minVal = minValAndCoords[0];
		int minX = minValAndCoords[1];
		int minY = minValAndCoords[2];
		System.out.println("Minimum steps on a tile is: " + minVal + " at (" + minX + ", " + minY + ")");
		maxVal = (maxVal == minVal ? maxVal + 1 : maxVal); // If minVal == maxVal, which is the case when a new
		// heatmap is created, it might cause division by zero, in which case we add 1 to max val.
		System.out.println("Number of tiles visited: " + heatmap.getNumTilesVisited());
		System.out.println("Step count: " + heatmap.getStepCount());

		// Create sorted heatmap tiles array (sorted left-to-right top-to-bottom)
		sortedHeatmapTiles = Arrays.copyOf(heatmap.getKeyValuesView().toArray(), heatmap.heatmapHashMap.size(), IntIntPair[].class);
		Arrays.sort(sortedHeatmapTiles, (tile1, tile2) -> {
			short[] coords1 = HeatmapNew.decodeIntCoordinate(tile1.getOne());
			short[] coords2 = HeatmapNew.decodeIntCoordinate(tile2.getOne());
			return compareNaturalReadingOrder(coords1[0], -coords1[1], coords2[0], -coords2[1]);
		});
	}

	/**
	 * @param x True gameworld X coordinate
	 * @param y True gameworld y coordinate
	 * @return The upper-left of the 9-pixel square location on the image osrs_world_map.png that this game
	 * coordinate responds to (1 game coordinate = 3x3 pixels). If it is out of bounds, then (-1, -1) is returned
	 */
	public static short[] gameCoordsToImageCoords(short x, short y)
	{
		short[] pixelLocation = new short[]{(short) (3 * (x + HEATMAP_OFFSET_X)), (short) (3 * (HEATMAP_HEIGHT - (y + HEATMAP_OFFSET_Y) - 1))};
		if (pixelLocation[0] < 0 || pixelLocation[1] < 0 || pixelLocation[0] > HEATMAP_WIDTH * 3 || pixelLocation[1] > HEATMAP_HEIGHT * 3)
		{
			return new short[]{-1, -1};
		}
		else
		{
			return pixelLocation;
		}
	}

	private static short[] imageCoordsToGameCoords(int xImage, int yImage)
	{
		short xGame = (short) (xImage / 3 - HEATMAP_OFFSET_X);
		short yGame = (short) (-yImage / 3 + HEATMAP_HEIGHT - 1 - HEATMAP_OFFSET_Y);
		return new short[]{xGame, yGame};
	}

	// Loads heatmap from local storage. If file does not exist, or an error occurs, it will return null.
	private static HeatmapNew readHeatmapFile(String filepath)
	{
		System.out.println("Loading heatmap file '" + filepath + "'");
		File heatmapFile = new File(filepath);
		if (!heatmapFile.exists())
		{
			// Return new blank heatmap if specified file doesn't exist
			System.err.println("World Heatmap was not able to load Worldheatmap file " + filepath + " because it " + "does" + " not exist.");
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
			long userID = (fieldValues[0].length() == 0 ? -1 : Long.parseLong(fieldValues[0]));
			int stepCount = (fieldValues[0].length() == 0 ? -1 : Integer.parseInt(fieldValues[2]));
			int maxVal = (fieldValues[0].length() == 0 ? -1 : Integer.parseInt(fieldValues[3]));
			int maxValX = (fieldValues[0].length() == 0 ? -1 : Integer.parseInt(fieldValues[4]));
			int maxValY = (fieldValues[0].length() == 0 ? -1 : Integer.parseInt(fieldValues[5]));
			int minVal = (fieldValues[0].length() == 0 ? -1 : Integer.parseInt(fieldValues[6]));
			int minValX = (fieldValues[0].length() == 0 ? -1 : Integer.parseInt(fieldValues[7]));
			int minValY = (fieldValues[0].length() == 0 ? -1 : Integer.parseInt(fieldValues[8]));

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
}
