/*
 * FindBugs - Find bugs in Java programs
 * Copyright (C) 2003, University of Maryland
 * 
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 * 
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 * 
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
 */

package edu.umd.cs.findbugs;

import java.io.*;
import java.net.URL;
import java.lang.reflect.Constructor;
import java.util.*;
import java.util.zip.*;
import edu.umd.cs.pugh.io.IO;
import edu.umd.cs.pugh.visitclass.Constants2;
import org.apache.bcel.classfile.*;
import org.apache.bcel.Repository;

public class FindBugs implements Constants2
{
  private static final boolean DEBUG = Boolean.getBoolean("findbugs.debug");

  private BugReporter bugReporter;
  private Detector detectors [];
  private LinkedList<String> detectorNames;
  private boolean omit;
  private HashMap<String, String> classNameToSourceFileMap;
  private FindBugsProgress progressCallback;

  public FindBugs(BugReporter bugReporter, LinkedList<String> detectorNames, boolean omit) {
	if (bugReporter == null)
		throw new IllegalArgumentException("null bugReporter");
	this.bugReporter = bugReporter;
	this.detectorNames = detectorNames;
	this.omit = omit;
	this.classNameToSourceFileMap = new HashMap<String, String>();

	// Create a no-op progress callback.
	this.progressCallback = new FindBugsProgress() {
		public void reportNumberOfArchives(int numArchives) { }
		public void finishArchive() { }
		public void startAnalysis(int numClasses) { }
		public void finishClass() { }
		public void finishPerClassAnalysis() { }
	};
  }

  public FindBugs(BugReporter bugReporter) {
	this(bugReporter, null, false);
  }

  /**
   * Set the progress callback that will be used to keep track
   * of the progress of the analysis.
   * @param progressCallback the progress callback
   */
  public void setProgressCallback(FindBugsProgress progressCallback) {
	this.progressCallback = progressCallback;
  }

  /**
   * Set filter of bug instances to include or exclude.
   */
  public void setFilter(String filterFileName, boolean include) throws IOException, FilterException {
	Filter filter = new Filter(filterFileName);
	bugReporter = new FilterBugReporter(bugReporter, filter, include);
  }

  private static ArrayList<DetectorFactory> factories = new ArrayList<DetectorFactory>();
  private static HashMap<String, DetectorFactory> factoriesByName = new HashMap<String, DetectorFactory>();
  private static IdentityHashMap<DetectorFactory, String> namesByFactory = new IdentityHashMap<DetectorFactory, String>();

  private Detector makeDetector(DetectorFactory factory) {
	return factory.create(bugReporter);
  }

  private static void registerDetector(DetectorFactory factory)  {
	String detectorName = factory.getShortName();
	factories.add(factory);
	factoriesByName.put(detectorName, factory);
	namesByFactory.put(factory, detectorName);
  }

  /**
   * Return an Iterator over the DetectorFactory objects for all
   * registered Detectors.
   */
  public static Iterator<DetectorFactory> factoryIterator() {
	return factories.iterator();
  }

  static {
	// Load all detector plugins.

	String homeDir = System.getProperty("findbugs.home");
	if (homeDir == null) {
		System.err.println("Error: The findbugs.home property is not set!");
		System.exit(1);
	}

	File pluginDir = new File(homeDir + File.separator + "plugin");
	File[] contentList = pluginDir.listFiles();
	if (contentList == null) {
		System.err.println("Error: The path " + pluginDir.getPath() + " does not seem to be a directory!");
		System.exit(1);
	}

	int numLoaded = 0;
	for (int i = 0; i < contentList.length; ++i) {
		File file = contentList[i];
		if (file.getName().endsWith(".jar")) {
			try {
				URL url = file.toURL();
				PluginLoader pluginLoader = new PluginLoader(url);

				// Register all of the detectors that this plugin contains
				DetectorFactory[] detectorFactoryList = pluginLoader.getDetectorFactoryList();
				for (int j = 0; j < detectorFactoryList.length; ++j)
					registerDetector(detectorFactoryList[j]);

				I18N i18n = I18N.instance();

				// Register the BugPatterns
				BugPattern[] bugPatternList = pluginLoader.getBugPatternList();
				for (int j = 0; j < bugPatternList.length; ++j)
					i18n.registerBugPattern(bugPatternList[j]);

				// Register the BugCodes
				BugCode[] bugCodeList = pluginLoader.getBugCodeList();
				for (int j = 0; j < bugCodeList.length; ++j)
					i18n.registerBugCode(bugCodeList[j]);

				++numLoaded;
			} catch (Exception e) {
				System.err.println("Warning: could not load plugin " + file.getPath() + ": " + e.toString());
			}
		}
	}

	//System.out.println("Loaded " + numLoaded + " plugins");
  }

  private void createDetectors() {
    if (detectorNames == null) {
	// Detectors were not named explicitly on command line,
	// so create all of them (except those that are disabled).

	ArrayList<Detector> result = new ArrayList<Detector>();

	Iterator<DetectorFactory> i = factories.iterator();
	int count = 0;
	while (i.hasNext()) {
		DetectorFactory factory = i.next();
		if (factory.isEnabled())
			result.add(makeDetector(factory));
	}

	detectors = result.toArray(new Detector[0]);
    } else {
	// Detectors were named explicitly on command line.

	if (!omit) {
		// Create only named detectors.
		detectors = new Detector[detectorNames.size()];
		Iterator i = detectorNames.iterator();
		int count = 0;
		while (i.hasNext()) {
			String name = (String) i.next();
			DetectorFactory factory = (DetectorFactory) factoriesByName.get(name);
			if (factory == null)
				throw new IllegalArgumentException("No such detector: " + name);
			detectors[count++] = makeDetector(factory);
		}
	} else {
		// Create all detectors EXCEPT named detectors.
		int numDetectors = factories.size() - detectorNames.size();
		detectors = new Detector[numDetectors];
		Iterator i = factories.iterator();
		int count = 0;
		while (i.hasNext()) {
			DetectorFactory factory = (DetectorFactory) i.next();
			String name = (String) namesByFactory.get(factory);
			if (!detectorNames.contains(name)) {
				// Add the detector.
				if (count == detectors.length)
					throw new IllegalArgumentException("bad omit list - nonexistent or duplicate detector specified?");
				detectors[count++] = makeDetector(factory);
			}
		}
		if (count != numDetectors) throw new IllegalStateException();
	}
    }
  }

  /**
   * Get the source file in which the given class is defined.
   * @param className fully qualified class name
   * @return name of the source file in which the class is defined
   */
  public String getSourceFile(String className) {
	return classNameToSourceFileMap.get(className);
  }

  /**
   * Add all classes contained in given file to the BCEL Repository.
   * @param fileName the file, which may be a jar/zip archive or a single class file
   */
  private void addFileToRepository(String fileName, List<String> repositoryClassList)
	throws IOException, InterruptedException {

     try {

	if (fileName.endsWith(".jar") || fileName.endsWith(".zip")) {
		ZipFile zipFile = new ZipFile(fileName);
		Enumeration entries = zipFile.entries();
		while (entries.hasMoreElements()) {
			if (Thread.interrupted())
				throw new InterruptedException();

			ZipEntry entry = (ZipEntry) entries.nextElement();
			String entryName = entry.getName();
			if (entryName.endsWith(".class")) {
				InputStream in = zipFile.getInputStream(entry);
				JavaClass javaClass = new ClassParser(in, entryName).parse();
				Repository.addClass(javaClass);
				repositoryClassList.add(javaClass.getClassName());
			}
		}
	} else {
		if (Thread.interrupted())
			throw new InterruptedException();
		JavaClass javaClass = new ClassParser(fileName).parse();
		Repository.addClass(javaClass);
		repositoryClassList.add(javaClass.getClassName());
	}

	progressCallback.finishArchive();

     } catch (IOException e) {
	// You'd think that the message for a FileNotFoundException would include
	// the filename, but you'd be wrong.  So, we'll add it explicitly.
	throw new IOException("Could not analyze " + fileName + ": " + e.getMessage());
     }
  }

  /**
   * Examine a single class by invoking all of the Detectors on it.
   * @param className the fully qualified name of the class to examine
   */
  private void examineClass(String className) throws InterruptedException {
	if (DEBUG) System.out.println("Examining class " + className);

	JavaClass javaClass;
	try {
		javaClass = Repository.lookupClass(className);
	} catch (ClassNotFoundException e) {
		throw new AnalysisException("Could not find class " + className + " in Repository", e);
	}

	classNameToSourceFileMap.put(javaClass.getClassName(), javaClass.getSourceFileName());
	ClassContext classContext = new ClassContext(javaClass);

	for (int i = 0; i < detectors.length; ++i) {
		if (Thread.interrupted())
			throw new InterruptedException();
		try {
			Detector detector = detectors[i];
			if (DEBUG) System.out.println("  running " + detector.getClass().getName());
			try {
				detector.visitClassContext(classContext);
			} catch (AnalysisException e) {
				bugReporter.logError(e.toString());
			}
		} catch (AnalysisException e) {
			bugReporter.logError("Analysis exception: " + e.toString());
		}
	}

	progressCallback.finishClass();
  }

  /**
   * Call report() on all detectors, to give them a chance to
   * report any accumulated bug reports.
   */
  public void reportFinal() throws InterruptedException {
	for (int i = 0; i < detectors.length; ++i) {
		if (Thread.interrupted())
			throw new InterruptedException();
		detectors[i].report();
	}
  }

  /**
   * Execute FindBugs on given list of files (which may be jar files or class files).
   * All bugs found are reported to the BugReporter object which was set
   * when this object was constructed.
   * @param argv list of files to analyze
   * @throws java.io.IOException if an I/O exception occurs analyzing one of the files
   * @throws InterruptedException if the thread is interrupted while conducting the analysis
   */
  public void execute(String[] argv) throws java.io.IOException, InterruptedException {
	if (detectors == null)
		createDetectors();

	// Purge repository of previous contents
	Repository.clearCache();

	progressCallback.reportNumberOfArchives(argv.length);

	List<String> repositoryClassList = new LinkedList<String>();

	for (int i = 0; i < argv.length; i++) {
		addFileToRepository(argv[i], repositoryClassList);
		}

	progressCallback.startAnalysis(repositoryClassList.size());

	for (Iterator<String> i = repositoryClassList.iterator(); i.hasNext(); ) {
		String className = i.next();
		examineClass(className);
		}

	progressCallback.finishPerClassAnalysis();

	this.reportFinal();

	// Flush any queued bug reports
	bugReporter.finish();

	// Flush any queued error reports
	bugReporter.reportQueuedErrors();
  }

  public static void main(String argv[]) throws Exception
  { 
	boolean quiet = false;
	boolean sortByClass = false;
	LinkedList<String> visitorNames = null;
	boolean omit = false;
	String filterFile = null;
	boolean include = false;

	// Process command line options
	int argCount = 0;
	while (argCount < argv.length) {
		String option = argv[argCount];
		if (!option.startsWith("-"))
			break;
		if (option.equals("-sortByClass"))
			sortByClass = true;
		else if (option.equals("-visitors") || option.equals("-omitVisitors")) {
			++argCount;
			if (argCount == argv.length) throw new IllegalArgumentException(option + " option requires argument");
			omit = option.equals("-omitVisitors");
			StringTokenizer tok = new StringTokenizer(argv[argCount], ",");
			visitorNames = new LinkedList<String>();
			while (tok.hasMoreTokens())
				visitorNames.add(tok.nextToken());
		} else if (option.equals("-exclude") || option.equals("-include")) {
			++argCount;
			if (argCount == argv.length) throw new IllegalArgumentException(option + " option requires argument");
			filterFile = argv[argCount];
			include = option.equals("-include");
		} else if (option.equals("-quiet")) {
			quiet = true;
		} else
			throw new IllegalArgumentException("Unknown option: " + option);
		++argCount;
	}

	if (argCount == argv.length) {
		InputStream in = FindBugs.class.getClassLoader().getResourceAsStream("USAGE");
		if (in == null)  {
			System.out.println("FindBugs tool, version " + Version.RELEASE);
			System.out.println("usage: java -jar findbugs.jar [options] <classfiles, zip files or jar files>");
			System.out.println("Example: java -jar findbugs.jar rt.jar");
			System.out.println("Options:");
			System.out.println("   -quiet                                 suppress error messages");
			System.out.println("   -sortByClass                           sort bug reports by class");
			System.out.println("   -visitors <visitor 1>,<visitor 2>,...  run only named visitors");
			System.out.println("   -omitVisitors <v1>,<v2>,...            omit named visitors");
			System.out.println("   -exclude <filter file>                 exclude bugs matching given filter");
			System.out.println("   -include <filter file>                 include only bugs matching given filter");
			}
		else
			IO.copy(in,System.out);
		return;
		}

	BugReporter bugReporter = sortByClass ? (BugReporter)new SortingBugReporter() : (BugReporter)new PrintingBugReporter();

	if (quiet)
		bugReporter.setErrorVerbosity(BugReporter.SILENT);

	FindBugs findBugs = new FindBugs(bugReporter, visitorNames, omit);

	if (filterFile != null)
		findBugs.setFilter(filterFile, include);

	String[] fileList = new String[argv.length - argCount];
	System.arraycopy(argv, argCount, fileList, 0, fileList.length);

	findBugs.execute(fileList);

  }
}
