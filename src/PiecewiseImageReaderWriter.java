import java.awt.image.RenderedImage;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import javax.imageio.IIOImage;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;

public class PiecewiseImageReaderWriter
{
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
		final int tileWidth = 8256;
		final int tileHeight = 496;
		final int N = reader.getWidth(0) / tileHeight;
		ImageWriter writer = ImageIO.getImageWritersByFormatName("tif").next();
		FileOutputStream fos = new FileOutputStream(outputImageFile);
		BufferedOutputStream bos = new BufferedOutputStream(fos);
		ImageOutputStream ios = ImageIO.createImageOutputStream(bos);
		writer.setOutput(ios);

		// Prepare writing parameters
		ImageWriteParam writeParam = writer.getDefaultWriteParam();
		writeParam.setTilingMode(ImageWriteParam.MODE_EXPLICIT);
		writeParam.setTiling(tileWidth, tileHeight, 0, 0);
		writeParam.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
		writeParam.setCompressionType("Deflate");
		writeParam.setCompressionQuality(0);

		// Write heatmap image
		RenderedImage heatmapImage = new HeatmapImage(new File("heatmap.heatmap"), reader, N);
		writer.write(null, new IIOImage(heatmapImage, null, null), writeParam);
		reader.dispose();
		writer.dispose();
	}
}
