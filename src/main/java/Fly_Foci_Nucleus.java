/*
 * Find nuclei and count foci dots inside 
 * Author: Philippe Mailly
 */

import Fly_Foci_Nucleus_Tools.Tools;
import ij.*;
import ij.plugin.PlugIn;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.logging.Level;
import java.util.logging.Logger;
import loci.common.services.DependencyException;
import loci.common.services.ServiceException;
import loci.common.services.ServiceFactory;
import loci.formats.FormatException;
import loci.formats.meta.IMetadata;
import loci.formats.services.OMEXMLService;
import loci.plugins.BF;
import loci.plugins.util.ImageProcessorReader;
import loci.plugins.in.ImporterOptions;
import mcib3d.geom2.Objects3DIntPopulation;
import org.apache.commons.io.FilenameUtils;
import org.scijava.util.ArrayUtils;



public class Fly_Foci_Nucleus implements PlugIn {
    
    Tools tools = new Tools();
    private boolean canceled = false;
    private String imageDir = "";
    public String outDirResults = "";
    private BufferedWriter outPutResults;
    
    
    public void run(String arg) {
        try {
            if (canceled) {
                IJ.showMessage("Plugin canceled");
                return;
            }
            if ((!tools.checkInstalledModules()) || (!tools.checkStarDistModels())) {
                return;
            }
            
            imageDir = IJ.getDirectory("Choose directory containing image files...");
            if (imageDir == null) {
                return;
            }   
            // Find images with file_ext extension
            String file_ext = tools.findImageType(new File(imageDir));
            ArrayList<String> imageFiles = tools.findImages(imageDir, file_ext);
            if (imageFiles == null) {
                IJ.showMessage("Error", "No images found with " + file_ext + " extension");
                return;
            }   
            
            // Create output folder
            outDirResults = imageDir + File.separator + "Results" + File.separator;
            File outDir = new File(outDirResults);
            if (!Files.exists(Paths.get(outDirResults))) {
                outDir.mkdir();
            }
            // Write header in results file
            String header = "Image name\tNucleus ID\tNucleus volume (µm3)\tNucleus intensity in channel 3\tFoci nb\t#Foci\tFoci volume(µm3)\tFoci sum intensity\n";
            FileWriter fwResults = new FileWriter(outDirResults + "results.xls", false);
            outPutResults = new BufferedWriter(fwResults);
            outPutResults.write(header);
            outPutResults.flush();
                      
            // Create OME-XML metadata store of the latest schema version
            ServiceFactory factory;
            factory = new ServiceFactory();
            OMEXMLService service = factory.getInstance(OMEXMLService.class);
            IMetadata meta = service.createOMEXMLMetadata();
            ImageProcessorReader reader = new ImageProcessorReader();
            reader.setMetadataStore(meta);
            reader.setId(imageFiles.get(0));
            // Find image calibration
            tools.cal = tools.findImageCalib(meta);
            
            // Find channel names
            String[] chsName = tools.findChannels(imageFiles.get(0), meta, reader);

            // Channels dialog
            String[] channels = tools.dialog(chsName);
            if (channels == null) {
                IJ.showStatus("Plugin cancelled");
                return;
            }         
            
            
            for (String f : imageFiles) {
                String rootName = FilenameUtils.getBaseName(f);
                System.out.println("--- ANALYZING IMAGE " + rootName + " ------");
                reader.setId(f);
                ImporterOptions options = new ImporterOptions();
                options.setId(f);
                options.setSplitChannels(true);
                options.setQuiet(true);
                options.setColorMode(ImporterOptions.COLOR_MODE_GRAYSCALE);
                
                // Open DAPI channel
                System.out.println("- Analyzing " + tools.channelNames[0] + " channel -");
                int indexCh = ArrayUtils.indexOf(chsName, channels[0]);
                ImagePlus imgDAPI = BF.openImagePlus(options)[indexCh];
                
                // Find DAPI nuclei with Cellpose
                System.out.println("Finding " + tools.channelNames[0] + " nuclei....");
                Objects3DIntPopulation nucPop = tools.cellposeDetection(imgDAPI, 0.5, true);
                System.out.println(nucPop.getNbObjects() + " " + tools.channelNames[0] + " nuclei found");
                tools.flush_close(imgDAPI);
                
                // Open Foci channel
                System.out.println("- Analyzing " + tools.channelNames[1] + " channel -");
                indexCh = ArrayUtils.indexOf(chsName, channels[1]);
                ImagePlus imgFoci = BF.openImagePlus(options)[indexCh];
                
                // Find PML foci with StarDist
                Objects3DIntPopulation fociPop = tools.stardistFociInNucleusPop(imgFoci, nucPop);
                System.out.println(fociPop.getNbObjects() + "Foci colocalized with " + tools.channelNames[0] + " nuclei");
               
                // Open channel3
                System.out.println("- Analyzing " + tools.channelNames[2] + " channel -");
                indexCh = ArrayUtils.indexOf(chsName, channels[2]);
                ImagePlus imgSC = BF.openImagePlus(options)[indexCh];
                
                // Save images
                tools.saveImgObjects(nucPop, fociPop, imgSC, rootName, outDirResults);
                
                // Write results
                tools.saveResults(nucPop, fociPop, imgFoci, imgSC, rootName, outPutResults);
                tools.flush_close(imgFoci);
                tools.flush_close(imgSC);
            }
            outPutResults.close();
        } catch (IOException | FormatException | DependencyException | ServiceException | io.scif.DependencyException ex) {
            Logger.getLogger(Fly_Foci_Nucleus.class.getName()).log(Level.SEVERE, null, ex);
        }
        System.out.println("--- All done! ---");
    }    
}    
