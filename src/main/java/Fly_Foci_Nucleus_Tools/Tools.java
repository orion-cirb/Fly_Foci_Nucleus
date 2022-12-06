package Fly_Foci_Nucleus_Tools;

import Fly_Cellpose.CellposeSegmentImgPlusAdvanced;
import Fly_Cellpose.CellposeTaskSettings;
import Fly_StardistOrion.StarDist2D;
import fiji.util.gui.GenericDialogPlus;
import ij.IJ;
import ij.ImagePlus;
import ij.Prefs;
import ij.io.FileSaver;
import ij.measure.Calibration;
import ij.plugin.Duplicator;
import ij.plugin.RGBStackMerge;
import ij.process.ImageProcessor;
import io.scif.DependencyException;
import java.awt.Color;
import java.awt.Font;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FilenameFilter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import javax.swing.ImageIcon;
import loci.common.services.ServiceException;
import loci.formats.FormatException;
import loci.formats.meta.IMetadata;
import loci.plugins.util.ImageProcessorReader;
import mcib3d.geom2.BoundingBox;
import mcib3d.geom2.Object3DInt;
import mcib3d.geom2.Objects3DIntPopulation;
import mcib3d.geom2.Objects3DIntPopulationComputation;
import mcib3d.geom2.measurements.MeasureIntensity;
import mcib3d.geom2.measurements.MeasureVolume;
import mcib3d.image3d.ImageHandler;
import net.haesleinhuepf.clij2.CLIJ2;
import org.apache.commons.io.FilenameUtils;
import org.scijava.util.ArrayUtils;
import mcib3d.geom2.measurements.MeasureCentroid;
import mcib3d.image3d.ImageInt;
import mcib3d.image3d.ImageLabeller;
import net.haesleinhuepf.clij.clearcl.ClearCLBuffer;


/**
 * @author phm
 */
public class Tools {
    private CLIJ2 clij2 = CLIJ2.getInstance();
    private final ImageIcon icon = new ImageIcon(this.getClass().getResource("/Orion_icon.png"));
    
    public String[] channelNames = {"DAPI", "Foci", "SC"};
    public Calibration cal = new Calibration();
    public double pixVol = 0;
    
    // Cellpose
    private final String cellposeEnvDirPath = (IJ.isLinux()) ? "/opt/miniconda3/envs/cellpose" : System.getProperty("user.home")+"\\miniconda3\\envs\\CellPose";
    private String cellposeModel = "cyto2";
    private int cellPoseNucDiameter = 60;
    
    //Stardist
    private Object syncObject = new Object();
    private final double stardistPercentileBottom = 0.2;
    private final double stardistPercentileTop = 99.8;
    private final double stardistProbThreshNuc = 0.5;
    private final double stardistOverlayThreshNuc = 0.25;
    public double stardistProbThreshFoci = 0.05;
    private final double stardistOverlayThreshFoci = 0.25;
    private File modelsPath = new File(IJ.getDirectory("imagej")+File.separator+"models");
    public String stardistNucModel = "StandardFluo.zip";
    public String stardistFociModel = "pmls2.zip";
    private String stardistOutput = "Label Image"; 
    
    private double minNucVol = 50;
    private double maxNucVol = 1000;
    private double minFociVol = 0.003;
    private double maxFociVol = 2;
    private double fociTh = 500;
    private String[] fociDetectionMethods = {"Stardist", "DOG", "LOG"};
    public String fociDetectionMethod = "";
    private double minFociDOG = 1;
    private double maxFociDOG = 2;
    
    
    /**
     * Check that needed modules are installed
     */
    public boolean checkInstalledModules() {
        ClassLoader loader = IJ.getClassLoader();
        try {
            loader.loadClass("mcib3d.geom.Object3D");
        } catch (ClassNotFoundException e) {
            IJ.showMessage("Error", "3D ImageJ Suite not installed, please install from update site");
            return false;
        }
        try {
            loader.loadClass("net.haesleinhuepf.clij2.CLIJ2");
        } catch (ClassNotFoundException e) {
            IJ.log("CLIJ not installed, please install from update site");
            return false;
        }
//        try {
//            loader.loadClass("uk.ac.sussex.gdsc-core-ij.ImageJUtils");
//        } catch (ClassNotFoundException e) {
//            IJ.log("GDSC not installed, please install from update site");
//            return false;
//        }
        return true;
    }
    
    
    /**
     * Check that required StarDist models are present in Fiji models folder
     */
    public boolean checkStarDistModels() {
        FilenameFilter filter = (dir, name) -> name.endsWith(".zip");
        File[] modelList = modelsPath.listFiles(filter);
        int index = ArrayUtils.indexOf(modelList, new File(modelsPath+File.separator+stardistNucModel));
        if (index == -1) {
            IJ.showMessage("Error", stardistNucModel + " StarDist model not found, please add it in Fiji models folder");
            return false;
        }
        index = ArrayUtils.indexOf(modelList, new File(modelsPath+File.separator+stardistFociModel));
        if (index == -1) {
            IJ.showMessage("Error", stardistFociModel + " StarDist model not found, please add it in Fiji models folder");
            return false;
        }
        return true;
    }
    
    
    /**
     * Find image type
     */
    public String findImageType(File imagesFolder) {
        String ext = "";
        String[] files = imagesFolder.list();
        for (String name : files) {
            String fileExt = FilenameUtils.getExtension(name);
            switch (fileExt) {
               case "nd" :
                   ext = fileExt;
                   break;
                case "czi" :
                   ext = fileExt;
                   break;
                case "lif"  :
                    ext = fileExt;
                    break;
                case "isc" :
                    ext = fileExt;
                    break;
                case "isc2" :
                    ext = fileExt;
                    break;
                case "lsm" :
                    ext = fileExt;
                    break;
                case "tif" :
                    ext = fileExt;
                    break;    
            }
        }
        return(ext);
    }
    
    
    /**
     * Find images in folder
     */
    public ArrayList<String> findImages(String imagesFolder, String imageExt) {
        File inDir = new File(imagesFolder);
        String[] files = inDir.list();
        if (files == null) {
            System.out.println("No image found in "+imagesFolder);
            return null;
        }
        ArrayList<String> images = new ArrayList();
        for (String f : files) {
            // Find images with extension
            String fileExt = FilenameUtils.getExtension(f);
            if (fileExt.equals(imageExt))
                images.add(imagesFolder + File.separator + f);
        }
        Collections.sort(images);
        return(images);
    }
    
    
    /**
     * Find image calibration
     */
    public Calibration findImageCalib(IMetadata meta) {
        cal.pixelWidth = meta.getPixelsPhysicalSizeX(0).value().doubleValue();
        cal.pixelHeight = cal.pixelWidth;
        if (meta.getPixelsPhysicalSizeZ(0) != null)
            cal.pixelDepth = meta.getPixelsPhysicalSizeZ(0).value().doubleValue();
        else
            cal.pixelDepth = 1;
        cal.setUnit("microns");
        System.out.println("XY calibration = " + cal.pixelWidth + ", Z calibration = " + cal.pixelDepth);
        return(cal);
    }
    
    
     /**
     * Find channels name
     * @throws loci.common.services.DependencyException
     * @throws loci.common.services.ServiceException
     * @throws loci.formats.FormatException
     * @throws java.io.IOException
     */
    public String[] findChannels (String imageName, IMetadata meta, ImageProcessorReader reader) throws DependencyException, ServiceException, FormatException, IOException {
        int chs = reader.getSizeC();
        String[] channels = new String[chs];
        String imageExt =  FilenameUtils.getExtension(imageName);
        switch (imageExt) {
            case "nd" :
                for (int n = 0; n < chs; n++) 
                {
                    if (meta.getChannelID(0, n) == null)
                        channels[n] = Integer.toString(n);
                    else 
                        channels[n] = meta.getChannelName(0, n).toString();
                }
                break;
            case "lif" :
                for (int n = 0; n < chs; n++) 
                    if (meta.getChannelID(0, n) == null || meta.getChannelName(0, n) == null)
                        channels[n] = Integer.toString(n);
                    else 
                        channels[n] = meta.getChannelName(0, n).toString();
                break;
            case "czi" :
                for (int n = 0; n < chs; n++) 
                    if (meta.getChannelID(0, n) == null)
                        channels[n] = Integer.toString(n);
                    else 
                        channels[n] = meta.getChannelFluor(0, n).toString();
                break;
            case "ics" :
                for (int n = 0; n < chs; n++) 
                    if (meta.getChannelID(0, n) == null)
                        channels[n] = Integer.toString(n);
                    else 
                        channels[n] = meta.getChannelExcitationWavelength(0, n).value().toString();
                break;    
            case "lsm" :
                for (int n = 0; n < chs; n++) 
                    if (meta.getChannelID(0, n) == null)
                        channels[n] = Integer.toString(n);
                    else 
                        channels[n] = channels[n] = meta.getChannelName(0, n).toString();
                break; 
            default :
                for (int n = 0; n < chs; n++)
                    channels[n] = Integer.toString(n);
        }
        return(channels);         
    }
    
        
    /**
     * Generate dialog box
     */
    public String[] dialog(String[] chs) {      
        GenericDialogPlus gd = new GenericDialogPlus("Parameters");
        gd.setInsets​(0, 100, 0);
        gd.addImage(icon);
        gd.addMessage("Channels", Font.getFont("Monospace"), Color.blue);
        int index = 0;
        for (String chNames : channelNames) {
            gd.addChoice(chNames+" : ", chs, chs[index]);
            index++;
        }
        
        gd.addMessage("Nuclei detection", Font.getFont("Monospace"), Color.blue);
        gd.addNumericField("Min nucleus volume (µm3): ", minNucVol);
        gd.addNumericField("Max nucleus volume (µm3): ", maxNucVol);
        
        gd.addMessage("Foci detection", Font.getFont("Monospace"), Color.blue);
        gd.addChoice("Foci detection method : ", fociDetectionMethods, fociDetectionMethods[0]);
        gd.addNumericField("Min foci volume (µm3): ", minFociVol);
        gd.addNumericField("Max foci volume (µm3): ", maxFociVol);
        gd.addNumericField("Foci intensity threshold : ", fociTh);
        
        gd.addMessage("Image calibration", Font.getFont("Monospace"), Color.blue);
        gd.addNumericField("XY pixel size (µm): ", cal.pixelWidth);
        gd.addNumericField("Z pixel size (µm): ", cal.pixelDepth);
        gd.showDialog();
        
        String[] chChoices = new String[channelNames.length];
        for (int n = 0; n < chChoices.length; n++) 
            chChoices[n] = gd.getNextChoice();
        if (gd.wasCanceled())
            chChoices = null;        

        minNucVol = gd.getNextNumber();
        maxNucVol = gd.getNextNumber();
        fociDetectionMethod = gd.getNextChoice();
        minFociVol = gd.getNextNumber();
        maxFociVol = gd.getNextNumber();
        fociTh = gd.getNextNumber();
        
        cal.pixelWidth = cal.pixelHeight = gd.getNextNumber();
        cal.pixelDepth = gd.getNextNumber();
        pixVol = cal.pixelWidth*cal.pixelHeight*cal.pixelDepth;  
        
        return(chChoices);
    }
     
    
    /**
     * Flush and close an image
     */
    public void flush_close(ImagePlus img) {
        img.flush();
        img.close();
    }
    
    
    /**
     * Remove object with only one plan
     * @param pop
     */
    public void popFilterOneZ(Objects3DIntPopulation pop) {
        pop.getObjects3DInt().removeIf(p -> (p.getObject3DPlanes().size() == 1));
        pop.resetLabels();
    }
    
    /**
     * Remove object with size < min and size > max
     * @param pop
     * @param min
     * @param max
     */
    public void popFilterSize(Objects3DIntPopulation pop, double min, double max) {
        pop.getObjects3DInt().removeIf(p -> (new MeasureVolume(p).getVolumeUnit() < min) || (new MeasureVolume(p).getVolumeUnit() > max));
        pop.resetLabels();
    }
    
    
    /**
     * Apply StarDist 2D slice by slice
     * Label detections in 3D
     * @return objects population
     */
   public Objects3DIntPopulation stardistNucleiPop(ImagePlus imgNuc) throws IOException{
       // Resize image to be in a StarDist-friendly scale
       ImagePlus img = null;
       float factor = 0.25f;
       boolean resize = false;
       if (imgNuc.getWidth() >= 1024) {
           img = imgNuc.resize((int)(imgNuc.getWidth()*factor), (int)(imgNuc.getHeight()*factor), 1, "none");
           resize = true;
       } else {
           img = new Duplicator().run(imgNuc);
       }
       
       // Remove outliers
       //IJ.run(img, "Remove Outliers", "block_radius_x=10 block_radius_y=10 standard_deviations=1 stack");
       
       // StarDist
       File starDistModelFile = new File(modelsPath+File.separator+stardistNucModel);
       StarDist2D star = new StarDist2D(syncObject, starDistModelFile);
       star.loadInput(img);
       star.setParams(stardistPercentileBottom, stardistPercentileTop, stardistProbThreshNuc, stardistOverlayThreshNuc, stardistOutput);
       star.run();
       flush_close(img);
       
       // Label detections in 3D
       ImagePlus imgOut = (resize) ? star.getLabelImagePlus().resize(imgNuc.getWidth(), imgNuc.getHeight(), 1, "none") : star.getLabelImagePlus();       
       ImagePlus imgLabels = star.associateLabels(imgOut);
       imgLabels.setCalibration(cal); 
       flush_close(imgOut);
       
       Objects3DIntPopulation pop = new Objects3DIntPopulation(ImageHandler.wrap(imgLabels));      
       Objects3DIntPopulation popFilter = new Objects3DIntPopulationComputation(pop).getExcludeBorders(ImageHandler.wrap(imgNuc), false);
       popFilterOneZ(popFilter);
       popFilterSize(popFilter, minNucVol, maxNucVol);
       flush_close(imgLabels);
       return(popFilter);
   }
   
   /**
     * Remove objects in population with intensity < intTh
     * @param pop
     * @param img
     * @param intTh 
     */
    public void intensityFilter(Objects3DIntPopulation pop, ImagePlus img, double intTh) {
        ImageHandler imh = ImageHandler.wrap(img);
        pop.getObjects3DInt().removeIf(p -> (new MeasureIntensity(p, imh).getValueMeasurement(MeasureIntensity.INTENSITY_SUM) < intTh));
        pop.resetLabels();
    }
    
    
    /**
     * Median filter using CLIJ2
     * @param img
     * @param sizeXY
     * @param sizeZ
     * @return 
     */ 
    public ImagePlus  median_filter(ImagePlus img, double sizeXY, double sizeZ) {
       ClearCLBuffer imgCL = clij2.push(img);
       ClearCLBuffer imgCLMed = clij2.create(imgCL);
       clij2.median3DBox(imgCL, imgCLMed, sizeXY, sizeXY, sizeZ);
       ImagePlus imgMed = clij2.pull(imgCLMed);
       return(imgMed);
    } 
    
     /**
     * Difference of Gaussians 
     * Using CLIJ2
     * @param img
     * @param size1
     * @param size2
     * @return imgGauss
     */ 
    public ImagePlus DOG(ImagePlus img, double size1, double size2) {
        ClearCLBuffer imgCL = clij2.push(img);
        ClearCLBuffer imgCLDOG = clij2.create(imgCL);
        clij2.differenceOfGaussian3D(imgCL, imgCLDOG, size1, size1, size1, size2, size2, size2);
        clij2.release(imgCL);
        ImagePlus imgDOG = clij2.pull(imgCLDOG);
        clij2.release(imgCLDOG);
        return(imgDOG);
    }
    
    
    /**
     * Laplace of Gaussians 
     * Using CLIJ2
     * @param imgCL
     * @param size
     * @return imgLOG
     */ 
    public ImagePlus laplacianOfGaussian(ImagePlus img, double size) {
        ClearCLBuffer imgCL = clij2.push(img);
        ClearCLBuffer temp1 = clij2.create(imgCL);
        ClearCLBuffer imgCLLOG = clij2.create(imgCL);
        clij2.gaussianBlur2D(imgCL, temp1, size, size);
        clij2.laplaceBox(temp1, imgCLLOG);
        clij2.release(imgCL);
        clij2.release(temp1);
        ImagePlus imgLog = clij2.pull(imgCLLOG);
        clij2.release(imgCLLOG);
        return(imgLog);
    }
    
    
    /**
     * Threshold 
     * USING CLIJ2
     * @param imgCL
     * @param thMed
     */
    public ImagePlus threshold(ImagePlus img, String thMed) {
        ClearCLBuffer imgCL = clij2.push(img);
        ClearCLBuffer imgCLBin = clij2.create(imgCL);
        clij2.automaticThreshold(imgCL, imgCLBin, thMed);
        clij2.release(imgCL);
        ImagePlus imgBin = clij2.pull(imgCLBin);
        clij2.release(imgCLBin);
        return(imgBin);
    }
    
    
   public Objects3DIntPopulation fociLOGDetection (ImagePlus imgFoci, Objects3DIntPopulation nucPop) {
       ImagePlus imgFilter = (fociDetectionMethod.equals("DOG") ? DOG(imgFoci, minFociDOG, maxFociDOG) : laplacianOfGaussian(imgFoci, maxFociDOG));
       ImagePlus imgBin = threshold(imgFilter, "Moments");
       flush_close(imgFilter);
       imgBin.setCalibration(cal);
       // label binary images first
       ImageLabeller labeller = new ImageLabeller();
       ImageInt labels = labeller.getLabels(ImageHandler.wrap(imgBin));
       flush_close(imgBin);
       Objects3DIntPopulation fociPop = new Objects3DIntPopulation(labels);
       labels.closeImagePlus();
       System.out.println("Found "+fociPop.getNbObjects()+" foci");
       popFilterSize(fociPop, minFociVol, maxFociVol);
       System.out.println("Found "+fociPop.getNbObjects()+" foci after size filter");
       intensityFilter(fociPop, imgFoci, fociTh);
       System.out.println("Found "+fociPop.getNbObjects()+" foci after intensity filter");
       // labels foci with nucleus label
       findFociInNucleusPop(nucPop, fociPop);
       System.out.println("Found "+fociPop.getNbObjects()+" foci in nucleus");
       return(fociPop);
   }
   
   
   /**
     * Apply StarDist 2D slice by slice
     * Label detections in 3D
     * @return objects population
     */
   public Objects3DIntPopulation stardistFociInNucleusPop(ImagePlus imgFoci, Objects3DIntPopulation nucPop) throws IOException{
       ImagePlus img = new Duplicator().run(imgFoci);
       IJ.run(img, "Median...", "radius=2 stack");
       // StarDist
       File starDistModelFile = new File(modelsPath+File.separator+stardistFociModel);
       StarDist2D star = new StarDist2D(syncObject, starDistModelFile);
       star.loadInput(img);
       star.setParams(stardistPercentileBottom, stardistPercentileTop, stardistProbThreshFoci, stardistOverlayThreshFoci, stardistOutput);
       star.run();
       flush_close(img);
       
       // Label detections in 3D
       ImagePlus imgOut = star.getLabelImagePlus();       
       ImagePlus imgLabels = star.associateLabels(imgOut);
       imgLabels.setCalibration(cal); 
       flush_close(imgOut);
       Objects3DIntPopulation fociPop = new Objects3DIntPopulation(ImageHandler.wrap(imgLabels));  
       System.out.println("Found "+fociPop.getNbObjects()+" foci");
       popFilterSize(fociPop, minFociVol, maxFociVol);
       System.out.println("Found "+fociPop.getNbObjects()+" foci after size filter");
       intensityFilter(fociPop, imgFoci, fociTh);
        System.out.println("Found "+fociPop.getNbObjects()+" foci after intensity filter");
       flush_close(imgLabels);
       // labels foci with nucleus label
       findFociInNucleusPop(nucPop, fociPop);
       System.out.println("Found "+fociPop.getNbObjects()+" foci in nucleus");
       return(fociPop);
   }
   
    /**
    * Detect Cells with CellPose
     * @param img
     * @param factor
     * @param resize
     * @return 
    */
    public Objects3DIntPopulation cellposeDetection(ImagePlus img, double factor, boolean resize) {
        // Resize image 
        int imgWidth = img.getWidth();
        int imgHeight = img.getHeight();
        ImagePlus imgIn = (resize) ? img.resize((int)(imgWidth*factor), (int)(imgHeight*factor), 1, "none") : new Duplicator().run(img);
        
        // Set Cellpose settings
        CellposeTaskSettings settings = new CellposeTaskSettings(cellposeModel, 1, cellPoseNucDiameter, cellposeEnvDirPath);
        settings.useGpu(true);
        settings.setFlowTh(0.4);
        settings.setStitchThreshold(0.25);
        // Run Omnipose
        CellposeSegmentImgPlusAdvanced cellpose = new CellposeSegmentImgPlusAdvanced(settings, imgIn);
        ImagePlus imgOut = (resize) ? cellpose.run().resize(imgWidth, imgHeight, 1, "none") : cellpose.run();   
        imgOut.setCalibration(cal);
        
        Objects3DIntPopulation pop = new Objects3DIntPopulation(ImageHandler.wrap(imgOut));
        Objects3DIntPopulation popFilter = new Objects3DIntPopulationComputation(pop).getExcludeBorders(ImageHandler.wrap(img), false);
        popFilterOneZ(popFilter);
        popFilterSize(popFilter, minNucVol, maxNucVol);
        // Close images
        flush_close(imgIn);
        flush_close(imgOut);
        return(popFilter);
    }
     
    /**
     * 
    */
    private Objects3DIntPopulation findFociNuc(float nucLabel, Objects3DIntPopulation fociPop) {
        Objects3DIntPopulation fociNucPop = new Objects3DIntPopulation();
        for (Object3DInt foci : fociPop.getObjects3DInt()) {
                if (foci.getIdObject() == nucLabel)
                    fociNucPop.addObject(foci);
        }
        fociNucPop.resetLabels();
        return(fociNucPop) ;           
    }
        
     /**
     * Compute nucleus parameters and save them in file
     * @param nucPop
     * @param fociPop
     * @param imgFoci
     * @param imgName
     * @param file
     * @throws java.io.IOException
     */
    public void saveResults(Objects3DIntPopulation nucPop, Objects3DIntPopulation fociPop, ImagePlus imgFoci, ImagePlus imgCh3, String imgName, 
            BufferedWriter file) throws IOException {
        for (Object3DInt nuc : nucPop.getObjects3DInt()) {
            float nucLabel = nuc.getLabel();
            double nucVol = new MeasureVolume(nuc).getValueMeasurement(MeasureVolume.VOLUME_UNIT);
            double nucInt = new MeasureIntensity(nuc, ImageHandler.wrap(imgCh3)).getValueMeasurement(MeasureIntensity.INTENSITY_SUM);
            Objects3DIntPopulation fociNucPop = findFociNuc(nucLabel, fociPop);
            int fociNb = fociNucPop.getNbObjects();
            file.write(imgName+"\t"+nucLabel+"\t"+nucVol+"\t"+nucInt+"\t"+fociNb+"\t");
            if (fociNb == 0)
                file.write("\n");
            for (Object3DInt foci : fociNucPop.getObjects3DInt()) {
                float fociLabel = foci.getLabel();
                double fociVol = new MeasureVolume(foci).getValueMeasurement(MeasureVolume.VOLUME_UNIT);
                double fociInt = new MeasureIntensity(foci, ImageHandler.wrap(imgFoci)).getValueMeasurement(MeasureIntensity.INTENSITY_SUM);
                if (fociLabel != 1)
                    file.write("\t\t\t\t\t");
                file.write(fociLabel+"\t"+fociVol+"\t"+fociInt+"\n");
                file.flush();
            }
        }
    }

     /**
     * Find coloc between foci and nucleus
     * set label of colocalized nucleus in foci object
     * @param nucPop
     * @param fociPop
     */
    public void findFociInNucleusPop(Objects3DIntPopulation nucPop, Objects3DIntPopulation fociPop) {
        if (nucPop.getNbObjects() != 0 && fociPop.getNbObjects() != 0) {
            for (Object3DInt nuc : nucPop.getObjects3DInt()) {
                for (Object3DInt foci : fociPop.getObjects3DInt()) {
                    MeasureCentroid fociCenter = new MeasureCentroid(foci);
                    if (nuc.contains(fociCenter.getCentroidRoundedAsVoxelInt())){
                       foci.setIdObject(nuc.getLabel()); 
                    }
                }
            }
        }
        // remove foci not in bacteria
        fociPop.getObjects3DInt().removeIf(p -> p.getIdObject() == 0);
        fociPop.resetLabels();
    }
    
    
    /**
     * Label object
     * @param popObj
     * @param img 
     * @param fontSize 
     */
    public void labelObject(Object3DInt obj, ImagePlus img, int fontSize) {
        if (IJ.isMacOSX())
            fontSize *= 3;
        
        BoundingBox bb = obj.getBoundingBox();
        int z = bb.zmin + 1;
        int x = bb.xmin;
        int y = bb.ymin;
        img.setSlice(z);
        ImageProcessor ip = img.getProcessor();
        ip.setFont(new Font("SansSerif", Font.PLAIN, fontSize));
        ip.setColor(255);
        ip.drawString(Integer.toString((int)obj.getLabel()), x, y);
        img.updateAndDraw();
    }
        
     
     /**
     * Save foci Population in image
     * @param nucPop nucleus blue channel
     * @param fociPop pml foci in green channel
     * @param img 
     * @param outDir 
     */
    public void saveImgObjects(Objects3DIntPopulation nucPop, Objects3DIntPopulation fociPop, ImagePlus img, String imageName, String outDir) {
        // Draw DAPI nuclei in blue
        ImageHandler imgObj1 = ImageHandler.wrap(img).createSameDimensions();
        ImageHandler imgObj2 = imgObj1.createSameDimensions();
        nucPop.drawInImage(imgObj1);
        if (nucPop.getNbObjects() > 0)
            for (Object3DInt obj: nucPop.getObjects3DInt())
                labelObject(obj, imgObj2.getImagePlus(), 20);
        
        // Draw foci in green
        ImageHandler imgObj3 = imgObj1.createSameDimensions();
        if (fociPop.getNbObjects() > 0)
            for (Object3DInt obj: fociPop.getObjects3DInt())
                obj.drawObject(imgObj3, 255);
        
        
        // Save image
        ImagePlus[] imgColors = {null, imgObj3.getImagePlus(), imgObj1.getImagePlus(), imgObj2.getImagePlus()};
        ImagePlus imgObjects = new RGBStackMerge().mergeHyperstacks(imgColors, false);
        imgObjects.setCalibration(img.getCalibration());
        FileSaver ImgObjectsFile = new FileSaver(imgObjects);
        ImgObjectsFile.saveAsTiff(outDir + imageName + "_" + fociDetectionMethod + ".tif"); 
        imgObj1.closeImagePlus();
        imgObj2.closeImagePlus();
        flush_close(imgObjects);
    }
}
