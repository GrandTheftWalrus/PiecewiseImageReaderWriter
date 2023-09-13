import org.eclipse.collections.api.block.procedure.primitive.IntIntProcedure;
import org.eclipse.collections.api.list.MutableList;
import org.eclipse.collections.api.tuple.primitive.IntIntPair;

import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.zip.InflaterInputStream;
import java.util.zip.ZipInputStream;

public class PiecewiseImageReaderWriter {
    private static final int HEATMAP_WIDTH = 2752;
    private static final int HEATMAP_HEIGHT = 1664;
    private static final int HEATMAP_OFFSET_X = -1152; // never change these
    private static final int HEATMAP_OFFSET_Y = -2496; // never change these
    private static final int HEATMAP_SENSITIVITY = 4;
    private static final int N = 10;
    private static final double HEATMAP_TRANSPARENCY = 0.65;
    private static int currentTileIndex = 0;
    // An array that holds the (encoded) heatmap coordinates along
    // with their values, sorted by coordinate left-to-right top-to-bottom
    private static IntIntPair[] sortedHeatmapTiles;
    static HeatmapNew heatmap = readHeatmapFile("heatmap.heatmap");
    private static boolean processingParametersInitialized = false;

    public static void main(String[] args) throws IOException, ClassNotFoundException {
        if (args.length != 2) {
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
        File outputImage = new File(outputImageName);
        if (inputStream == null) {
            System.err.println("Error: resource \"" + inputImageName + "\" doesn't exist");
            System.exit(-1);
        }

        // Prepare the image reader
        Iterator readers = ImageIO.getImageReadersByFormatName("png");
        ImageReader reader = (ImageReader) readers.next();
        ImageInputStream iis = ImageIO.createImageInputStream(inputStream);
        reader.setInput(iis, true);

        // N times:
        for (int i = 0; i < N; i++) {
            System.out.println("Reading chunk " + (i + 1) + "/" + N + "...");
            // Read a horizontal stripe of the image that's 1/nth of the height
            ImageReadParam param = reader.getDefaultReadParam();
            int imageIndex = 0;
            int xOffset = 0;
            int yOffset = reader.getHeight(imageIndex) / N * (i);
            int width = reader.getWidth(imageIndex);
            int height = reader.getHeight(imageIndex) / N;
            Rectangle rect = new Rectangle(xOffset, yOffset, width, height);
            param.setSourceRegion(rect);
            BufferedImage bi = reader.read(imageIndex, param);

            // Edit the image region
            bi = processImageRegion(bi, xOffset, yOffset, width, height, heatmap);

            // Write the region to the image in the ImageWriter
            ImageIO.write(bi, "png", new File(outputImageName + "_" + i));
        }

        // Close the image reader
        // Finalize the written image file
    }

    public static BufferedImage processImageRegion(BufferedImage imageRegion, int regionX, int regionY, int regionWidth, int regionHeight, HeatmapNew heatmap) {
        // Initialize values
        if (!processingParametersInitialized) {
            System.out.println("Initializing phase arrays...");
            initializeProcessingParameters();
            processingParametersInitialized = true;
        }

        // Run them heatmap tiles through the ol' rigamarole
        long startTime = System.nanoTime();
        int numTilesProcessed = 0;
        try {
            int currRGB = 0;
            double currHue = 0; // a number on the interval [0, 1] that will represent the intensity of the current heatmap pixel
            double minHue = 1 / 3., maxHue = 0;
            double nthRoot = 1 + (HEATMAP_SENSITIVITY - 1.0) / 2;
            int LOG_BASE = 4;

            // For each pixel in current image region
            while (currentTileIndex < sortedHeatmapTiles.length) {
                IntIntPair tile = sortedHeatmapTiles[currentTileIndex++];
                numTilesProcessed++;
                short[] coords = HeatmapNew.decodeIntCoordinate(tile.getOne());
                coords = gameCoordsToImageCoords(coords[0], coords[1]);
                boolean isInImageBounds = (coords[0] > 0 && coords[1] > 0);
                int x = coords[0];
                int y = coords[1];
                int tileValue = tile.getTwo();

                int comparison1 = compareNaturalReadingOrder(x, y, regionX, regionY);
                int comparison2 = compareNaturalReadingOrder(x, y, regionX + regionWidth, regionY + regionHeight);
                // If current tile is after bottom right edge of current image region in reading order, return
                if (comparison2 > 0) break;
                // If current tile is before upper left edge of current image region, or is out of bounds of the overworld, or hasn't been stepped on, skip
                if (comparison1 < 0 || !isInImageBounds || tileValue == 0) continue;
                // Else continue

                // Calculate normalized step value
                currHue = (float) ((Math.log(tileValue) / Math.log(LOG_BASE)) / (Math.log(heatmap.getMaxVal()[0] + 1 - heatmap.getMinVal()[0]) / Math.log(LOG_BASE)));
                currHue = Math.pow(currHue, 1.0 / nthRoot);
                currHue = (float) (minHue + (currHue * (maxHue - minHue))); // Assign a hue based on normalized step value (values [0, 1] are mapped linearly to hues of [0, 0.333] aka green then yellow, then red)

                // Reassign the new RGB values to the corresponding 9 pixels (we scale by a factor of 3)
                for (int x_offset = 0; x_offset <= 2; x_offset++) {
                    for (int y_offset = 0; y_offset <= 2; y_offset++) {
                        int curX = x - regionX + x_offset;
                        int curY = y - regionY + y_offset;
                        if (curX >= imageRegion.getWidth() || curY >= imageRegion.getHeight())
                            continue;
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
            System.out.println("Finished processing image chunk after " + (System.nanoTime() - startTime) / 1_000_000 + " ms (" + numTilesProcessed + " tiles)");
        } catch (OutOfMemoryError e) {
            e.printStackTrace();
            System.err.println("OutOfMemoryError thrown whilst creating and/or writing image file." + "Perhaps try making a Runelite plugin profile with no other plugins enabled" + "besides World Heatmap. If it works then, then you might have too many plugins" + "running. If not, then I unno chief, perhaps you should submit an issue on the" + "GitHub.");
        } catch (Exception e) {
            e.printStackTrace();
            System.err.println("Exception thrown whilst processing image region.");
        } finally {
            return imageRegion;
        }
    }

    private static int compareNaturalReadingOrder(int x1, int y1, int x2, int y2) {
        if (y1 < y2) return -1;
        if (y1 > y2) return 1;
        else return x1 - x2;
    }

    private static void initializeProcessingParameters() {
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
        maxVal = (maxVal == minVal ? maxVal + 1 : maxVal); // If minVal == maxVal, which is the case when a new heatmap is created, it might cause division by zero, in which case we add 1 to max val.
        System.out.println("Number of tiles visited: " + heatmap.getNumTilesVisited());
        System.out.println("Step count: " + heatmap.getStepCount());

        // Create sorted heatmap tiles array (sorted left-to-right top-to-bottom)
        sortedHeatmapTiles = Arrays.copyOf(heatmap.getKeyValuesView().toArray(), heatmap.heatmapHashMap.size(), IntIntPair[].class);
        Arrays.sort(sortedHeatmapTiles, (tile1, tile2) -> {
            short[] coords1 = HeatmapNew.decodeIntCoordinate(tile1.getOne());
            short[] coords2 = HeatmapNew.decodeIntCoordinate(tile2.getOne());
            return compareNaturalReadingOrder(coords1[0], -coords1[1], coords2[0], -coords2[1]);
        });

//        sortedHeatmapTiles = new long[heatmap.heatmapHashMap.size()];
//        for (IntIntPair pair : heatmap.getKeyValuesView())
//            sortedHeatmapTiles.add((long)pair.getOne() << 32 + (long)pair.getTwo());
//
//        sortedHeatmapTiles.sort((tile1, tile2) -> {
//            short[] coords1 = HeatmapNew.decodeIntCoordinate((int) (tile1 >>> 32));
//            short[] coords2 = HeatmapNew.decodeIntCoordinate((int) (tile2 >>> 32));
//            return compareNaturalReadingOrder(coords1[0], -coords1[1], coords2[0], -coords2[1]);
//        });

//        sortedHeatmapTiles = Arrays.stream(heatmap.getKeyValuesView().toArray()).sorted((tile1, tile2) -> {
//            short[] coords1 = HeatmapNew.decodeIntCoordinate(((IntIntPair) tile1).getOne());
//            short[] coords2 = HeatmapNew.decodeIntCoordinate(((IntIntPair) tile2).getOne());
//            return compareNaturalReadingOrder(coords1[0], -coords1[1], coords2[0], -coords2[1]);
//        }).iterator();

        // Verify that the tiles are in order
//        for (IntIntPair pair : sortedHeatmapTiles){
//            short[] coords = HeatmapNew.decodeIntCoordinate(pair.getOne());
//            int[] xy = gameCoordsToImageCoords(coords[0], coords[1]);
//            System.out.printf("(%5d, %5d)\n", xy[0], xy[1]);
//        }
    }

    /**
     * @param x True gameworld X coordinate
     * @param y True gameworld y coordinate
     * @return The upper-left of the 9-pixel square location on the image osrs_world_map.png that this game coordinate responds to (1 game coordinate = 3x3 pixels). If it is out of bounds, then (-1, -1) is returned
     */
    public static short[] gameCoordsToImageCoords(short x, short y) {
        short[] pixelLocation = new short[]{(short) (3 * (x + HEATMAP_OFFSET_X)), (short) (3 * (HEATMAP_HEIGHT - (y + HEATMAP_OFFSET_Y) - 1))};
        if (pixelLocation[0] < 0 || pixelLocation[1] < 0 || pixelLocation[0] > HEATMAP_WIDTH * 3 || pixelLocation[1] > HEATMAP_HEIGHT * 3)
            return new short[]{-1, -1};
        else return pixelLocation;
    }

    // Loads heatmap from local storage. If file does not exist, or an error occurs, it will return null.
    private static HeatmapNew readHeatmapFile(String filepath) {
        System.out.println("Loading heatmap file '" + filepath + "'");
        File heatmapFile = new File(filepath);
        if (!heatmapFile.exists()) {
            // Return new blank heatmap if specified file doesn't exist
            System.err.println("World Heatmap was not able to load Worldheatmap file " + filepath + " because it does not exist.");
            return null;
        }
        // Detect whether the .heatmap file is the old style (serialized Heatmap, rather than a zipped .CSV file)
        // And if it is, then convert it to the new style
        try (FileInputStream fis = new FileInputStream(filepath)) {
            InflaterInputStream iis = new InflaterInputStream(fis);
            ObjectInputStream ois = new ObjectInputStream(iis);
            Object heatmap = ois.readObject();
            if (heatmap instanceof Heatmap) {
                System.out.println("Attempting to convert old-style heatmap file to new style...");
                long startTime = System.nanoTime();
                HeatmapNew result = HeatmapNew.convertOldHeatmapToNew((Heatmap) heatmap);
                System.out.println("Finished converting old-style heatmap to new style in " + (System.nanoTime() - startTime) / 1_000_000 + " ms");
                return result;
            }
        } catch (Exception e) {
            // If reached here, then the file was not of the older type.
        }
        // Test if it is of the newer type, or something else altogether.
        try (FileInputStream fis = new FileInputStream(filepath)) {
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
            if (userID != -1) heatmapNew = new HeatmapNew(userID);
            else heatmapNew = new HeatmapNew();
            heatmapNew.maxVal = new int[]{maxVal, maxValX, maxValY};
            heatmapNew.minVal = new int[]{minVal, minValX, minValY};
            heatmapNew.stepCount = stepCount;

            // Read the tile values
            final int[] errorCount = {0}; // Number of parsing errors occurred during read
            reader.lines().forEach(s -> {
                String[] tile = s.split(",");
                try {
                    heatmapNew.setFast(Short.parseShort(tile[0]), Short.parseShort(tile[1]), Integer.parseInt(tile[2]));
                } catch (NumberFormatException e) {
                    errorCount[0]++;
                }
            });
            if (errorCount[0] > 0) {
                System.err.println(errorCount[0] + " errors occurred during heatmap file read.");
            }
            return heatmapNew;
        } catch (Exception e) {
            e.printStackTrace();
            System.err.println("The file " + filepath + " is not a heatmap file or it is corrupt, or something.");
        }
        return null;
    }
}
