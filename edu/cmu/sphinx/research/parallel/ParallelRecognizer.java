/*
 * Copyright 1999-2004 Carnegie Mellon University.  
 * Portions Copyright 2004 Sun Microsystems, Inc.  
 * Portions Copyright 2004 Mitsubishi Electronic Research Laboratories.
 * All Rights Reserved.  Use is subject to license terms.
 * 
 * See the file "license.terms" for information on usage and
 * redistribution of this file, and for a DISCLAIMER OF ALL 
 * WARRANTIES.
 *
 */
package edu.cmu.sphinx.research.parallel;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;

import java.net.URL;

import java.util.Iterator;
import java.util.List;

import edu.cmu.sphinx.frontend.DataProcessor;
import edu.cmu.sphinx.frontend.util.StreamCepstrumSource;
import edu.cmu.sphinx.frontend.util.StreamDataSource;

import edu.cmu.sphinx.recognizer.Recognizer;

import edu.cmu.sphinx.util.BatchItem;
import edu.cmu.sphinx.util.BatchManager;
import edu.cmu.sphinx.util.PooledBatchManager;
import edu.cmu.sphinx.util.SimpleBatchManager;
import edu.cmu.sphinx.util.Utilities;
import edu.cmu.sphinx.util.props.Configurable;
import edu.cmu.sphinx.util.props.ConfigurationManager;
import edu.cmu.sphinx.util.props.PropertyException;
import edu.cmu.sphinx.util.props.PropertySheet;
import edu.cmu.sphinx.util.props.PropertyType;
import edu.cmu.sphinx.util.props.Registry;

/**
 * Uses multiple feature streams to decode a list of raw audio files.
 * The {@link #PROP_DATA_SOURCES data sources} are a list of DataProcessor
 * objects, one for each feature stream.
 */
public class ParallelRecognizer implements Configurable {

    /**
     * The SphinxProperty name for how many files to skip for every decode.
     */
    public final static String PROP_SKIP = "skip";

    /**
     * The default value for the property PROP_SKIP.
     */
    public final static int PROP_SKIP_DEFAULT = 0;

    /**
     * The SphinxProperty that specified which batch job is to be run.
     *  
     */
    public final static String PROP_WHICH_BATCH = "whichBatch";

    /**
     * The default value for the property PROP_WHICH_BATCH.
     */
    public final static int PROP_WHICH_BATCH_DEFAULT = 0;

    /**
     * The SphinxProperty for the total number of batch jobs the decoding run
     * is being divided into.
     * 
     * The BatchDecoder supports running a subset of a batch. This allows a
     * test to be distributed among several machines.
     *  
     */
    public final static String PROP_TOTAL_BATCHES = "totalBatches";

    /**
     * The default value for the property PROP_TOTAL_BATCHES.
     */
    public final static int PROP_TOTAL_BATCHES_DEFAULT = 1;

    /**
     * The SphinxProperty that defines whether or not the decoder should use
     * the pooled batch manager
     */
    public final static String PROP_USE_POOLED_BATCH_MANAGER = "usePooledBatchManager";

    /**
     * The default value for the property PROP_USE_POOLED_BATCH_MANAGER.
     */
    public final static boolean PROP_USE_POOLED_BATCH_MANAGER_DEFAULT = false;

    /**
     * The Sphinx property that specifies the recognizer to use
     */
    public final static String PROP_RECOGNIZER = "recognizer";

    /**
     * The sphinx property that specifies the input source
     */
    public final static String PROP_DATA_SOURCES = "dataSources";


    // -------------------------------
    // Configuration data
    // --------------------------------

    private String name;
    private List dataSources;
    private int skip;
    private int whichBatch;
    private int totalBatches;
    private boolean usePooledBatchManager;
    private BatchManager batchManager;
    private Recognizer recognizer;

    /*
     * (non-Javadoc)
     * 
     * @see edu.cmu.sphinx.util.props.Configurable#register(java.lang.String,
     *      edu.cmu.sphinx.util.props.Registry)
     */
    public void register(String name, Registry registry)
	throws PropertyException {
        this.name = name;
        registry.register(PROP_SKIP, PropertyType.INT);
        registry.register(PROP_WHICH_BATCH, PropertyType.INT);
        registry.register(PROP_TOTAL_BATCHES, PropertyType.INT);
        registry.register(PROP_USE_POOLED_BATCH_MANAGER, PropertyType.BOOLEAN);
        registry.register(PROP_RECOGNIZER, PropertyType.COMPONENT);
        registry.register(PROP_DATA_SOURCES, PropertyType.COMPONENT_LIST);
    }

    /*
     * (non-Javadoc)
     * 
     * @see edu.cmu.sphinx.util.props.Configurable#newProperties(edu.cmu.sphinx.util.props.PropertySheet)
     */
    public void newProperties(PropertySheet ps) throws PropertyException {
        skip = ps.getInt(PROP_SKIP, PROP_SKIP_DEFAULT);
        whichBatch = ps.getInt(PROP_WHICH_BATCH, PROP_WHICH_BATCH_DEFAULT);
        totalBatches = ps
	    .getInt(PROP_TOTAL_BATCHES, PROP_TOTAL_BATCHES_DEFAULT);
        usePooledBatchManager = ps.getBoolean(PROP_USE_POOLED_BATCH_MANAGER,
					      PROP_USE_POOLED_BATCH_MANAGER_DEFAULT);
        recognizer = (Recognizer) ps.getComponent(PROP_RECOGNIZER,
						  Recognizer.class);
        dataSources = ps.getComponentList(PROP_DATA_SOURCES,
                                          DataProcessor.class);
    }

    /*
     * (non-Javadoc)
     * 
     * @see edu.cmu.sphinx.util.props.Configurable#getName()
     */
    public String getName() {
        return name;
    }

    /**
     * Sets the batch file to use for this recogition
     * 
     * @param batchFile
     *                the name of the batch file
     * @throws IOException
     *                 if the file could not be opened or read.
     */
    public void setBatchFile(String batchFile) throws IOException {
        if (usePooledBatchManager) {
            batchManager = new PooledBatchManager(batchFile, skip);
        } else {
            batchManager = new SimpleBatchManager(batchFile, skip, whichBatch,
						  totalBatches);
        }
    }

    /**
     * Decodes the batch of audio files
     * 
     * @throws IOException
     *                 if there is an I/O error processing the batch file
     */
    public void decode(String batchFile) {
        BatchItem batchItem;
        try {
            recognizer.allocate();
            setBatchFile(batchFile);

            batchManager.start();
            System.out.println("\nBatchDecoder: decoding files in "
			       + batchManager.getFilename());
            System.out.println();
        
            while ((batchItem = batchManager.getNextItem()) != null) {
                setInputStream(batchItem.getFilename());
                recognizer.recognize(batchItem.getTranscript());
            }
            batchManager.stop();
        } catch (IOException io) {
            System.err.println("I/O error during decoding: " + io.getMessage());
	    io.printStackTrace();
        }
        recognizer.deallocate();
        System.out.println("\nBatchDecoder: All files decoded\n");
    }
    
    
    /**
     * Sets the input stream to the given filename
     *
     * @param filename the filename to set the input stream to
     *
     * @throws IOException if an error occurs
     */
    private void setInputStream(String filename) throws IOException {
        for (Iterator i = dataSources.iterator(); i.hasNext(); ) {
            DataProcessor dataSource = (DataProcessor) i.next();
            InputStream is = new FileInputStream(filename);
            if (dataSource instanceof StreamDataSource) {
                ((StreamDataSource) dataSource).setInputStream(is, filename);
            } else if (dataSource instanceof StreamCepstrumSource) {
                boolean isBigEndian = Utilities
                    .isCepstraFileBigEndian(filename);
                StreamCepstrumSource cepstrumSource = 
                    (StreamCepstrumSource) dataSource;
                cepstrumSource.setInputStream(is, isBigEndian);
            }
        }
    }

    /**
     * Main method of this BatchDecoder.
     * 
     * @param argv
     *                argv[0] : confif.xml argv[1] : a file listing
     *                all the audio files to decode
     */
    public static void main(String[] argv) {
        if (argv.length < 2) {
            System.out.println("Usage: BatchDecoder propertiesFile batchFile");
            System.exit(1);
        }
        String cmFile = argv[0];
        String batchFile = argv[1];
        ConfigurationManager cm;
        ParallelRecognizer bmr = null;

        ParallelRecognizer recognizer;
        try {
            URL url = new File(cmFile).toURI().toURL();
            cm = new ConfigurationManager(url);
            bmr = (ParallelRecognizer) cm.lookup("parallel");
        } catch (IOException ioe) {
            System.err.println("I/O error during initialization: \n   " + ioe);
            return;
        } catch (InstantiationException e) {
            System.err.println("Error during initialization: \n  " + e);
            return;
        } catch (PropertyException e) {
            System.err.println("Error during initialization: \n  " + e);
            e.printStackTrace();
            return;
        }

        if (bmr == null) {
            System.err.println("Can't find ParallelRecognizer in " + cmFile);
            return;
        }

        bmr.decode(batchFile);
    }
}