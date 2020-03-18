/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2013, Red Hat, Inc., and individual contributors
 * as indicated by the @author tags. See the copyright.txt file in the
 * distribution for a full listing of individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */

package org.jboss.as.patching.generator;

import static java.lang.System.getProperty;
import static java.lang.System.getSecurityManager;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import javax.xml.stream.XMLStreamException;

import org.jboss.as.patching.IoUtils;
import org.jboss.as.patching.PatchingException;
import org.jboss.as.patching.ZipUtils;
import org.jboss.as.patching.logging.PatchLogger;
import org.jboss.as.patching.metadata.Patch;
import org.jboss.as.patching.metadata.PatchMerger;
import org.jboss.as.version.ProductConfig;
import org.jboss.modules.Module;

/**
 * Generates a patch archive.
 * Run it using JBoss modules:
 * <pre><code>
 *   java -jar jboss-modules.jar -mp modules/ org.jboss.as.patching.generator
 * </code></pre>
 *
 * @author Brian Stansberry (c) 2012 Red Hat Inc.
 */
public class PatchGenerator {

    public static final String APPLIES_TO_DIST = "--applies-to-dist";
    public static final String ASSEMBLE_PATCH_BUNDLE = "--assemble-patch-bundle";
    public static final String CREATE_TEMPLATE = "--create-template";
    public static final String DETAILED_INSPECTION = "--detailed-inspection";
    public static final String INCLUDE_VERSION = "--include-version";
    public static final String COMBINE_WITH = "--combine-with";
    public static final String OUTPUT_FILE = "--output-file";
    public static final String PATCH_CONFIG = "--patch-config";
    public static final String UPDATED_DIST = "--updated-dist";
    public static final String SKIP_LAYERS_NO_CONFIG = "--skip-layers-no-config";
    public static final String SKIP_MISC_FILES = "--skip-misc-files";
    public static final String INCLUDED_MISC_FILES = "--included-misc-files";

    public static void main(String[] args) {
        try {
            PatchGenerator patchGenerator = parse(args);
            if (patchGenerator != null) {
                patchGenerator.process();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private final boolean includeVersion;
    private final File patchConfigFile;
    private final File oldRoot;
    private final File newRoot;
    private File patchFile;
    private final File previousCp;
    private final boolean skipLayersNoConfig;
    private final SkipMiscFilesContentItemFilter skipMiscFilesContentItemFilter;
    private File tmp;

    private PatchGenerator(File patchConfig, File oldRoot, File newRoot,
                           File patchFile, boolean includeVersion, File previousCp,
                           boolean skipLayersNoConfig, SkipMiscFilesContentItemFilter skipMiscFilesContentItemFilter) {
        this.patchConfigFile = patchConfig;
        this.oldRoot = oldRoot;
        this.newRoot = newRoot;
        this.patchFile = patchFile;
        this.includeVersion = includeVersion;
        this.previousCp = previousCp;
        this.skipLayersNoConfig = skipLayersNoConfig;
        this.skipMiscFilesContentItemFilter = skipMiscFilesContentItemFilter;
    }

    private void process() throws PatchingException, IOException, XMLStreamException {

        try {
            PatchConfig patchConfig = parsePatchConfig();

            Set<String> required = new TreeSet<>();
            if (newRoot == null) {
                required.add(UPDATED_DIST);
            }
            if (oldRoot == null) {
                required.add(APPLIES_TO_DIST);
            }
            if (patchFile == null) {
                if (newRoot != null) {
                    patchFile = new File(newRoot, "patch-" + System.currentTimeMillis() + ".par");
                } else {
                    required.add(OUTPUT_FILE);
                }
            }
            if (!required.isEmpty()) {
                System.err.printf(PatchGenLogger.missingRequiredArgs(required));
                usage();
                return;
            }

            createTempStructure(patchConfig.getPatchId());

            // See whether to include the updated version information
            boolean includeVersion = patchConfig.getPatchType() == Patch.PatchType.CUMULATIVE ? true : this.includeVersion;
            final String[] ignored = includeVersion ? new String[0] : new String[] {"org/jboss/as/product", "org/jboss/as/version"};

            // Create the distributions
            final Distribution base = Distribution.create(oldRoot, ignored);
            final Distribution updated = Distribution.create(newRoot, ignored);

            if (!base.getName().equals(updated.getName())) {
                throw processingError("distribution names don't match, expected: %s, but was %s ", base.getName(), updated.getName());
            }
            //
            if (patchConfig.getAppliesToProduct() != null && ! patchConfig.getAppliesToProduct().equals(base.getName())) {
                throw processingError("patch target does not match, expected: %s, but was %s", patchConfig.getAppliesToProduct(), base.getName());
            }
            //
            if (patchConfig.getAppliesToVersion() != null && ! patchConfig.getAppliesToVersion().equals(base.getVersion())) {
                throw processingError("patch target version does not match, expected: %s, but was %s", patchConfig.getAppliesToVersion(), base.getVersion());
            }

            // Build the patch metadata
            final PatchBuilderWrapper builder = patchConfig.toPatchBuilder(skipMiscFilesContentItemFilter, skipLayersNoConfig);
            builder.setPatchId(patchConfig.getPatchId());
            builder.setDescription(patchConfig.getDescription());
            builder.setOptionalPaths(patchConfig.getOptionalPaths());
            if (patchConfig.getPatchType() == Patch.PatchType.CUMULATIVE) {
                // CPs need to upgrade
                if (base.getVersion().equals(updated.getVersion())) {
                    System.out.println("WARN: cumulative patch does not upgrade version " + base.getVersion());
                }
                builder.upgradeIdentity(base.getName(), base.getVersion(), updated.getVersion());
            } else {
                builder.oneOffPatchIdentity(base.getName(), base.getVersion());
            }

            // Create the resulting patch
            final Patch patch = builder.compare(base, updated, includeVersion);

            // Copy the contents to the temp dir structure
            PatchContentWriter.process(tmp, newRoot, patch);

            if(previousCp != null) {
                PatchMerger.merge(previousCp, tmp, patchFile);
            } else {
                ZipUtils.zip(tmp, patchFile);
            }

        } finally {
            IoUtils.recursiveDelete(tmp);
        }

    }

    private PatchConfig parsePatchConfig() throws FileNotFoundException, XMLStreamException {
        FileInputStream fis = null;
        try {
            fis = new FileInputStream(patchConfigFile);
            BufferedInputStream bis = new BufferedInputStream(fis);
            return PatchConfigXml.parse(bis);
        } finally {
            IoUtils.safeClose(fis);
        }
    }

    private void createTempStructure(String patchId) {

        File tmpDir = new File(System.getProperty("java.io.tmpdir"));
        int count = 0;
        while (tmp == null || tmp.exists()) {
            count++;
            tmp = new File(tmpDir, "jboss-as-patch-" + patchId + "-" + count);
        }
        if (!tmp.mkdirs()) {
            throw processingError("Cannot create tmp dir for patch create at %s", tmp.getAbsolutePath());
        }
        tmp.deleteOnExit();
        File metaInf = new File(tmp, "META-INF");
        metaInf.mkdir();
        metaInf.deleteOnExit();
        File misc = new File(tmp, "misc");
        misc.mkdir();
        misc.deleteOnExit();
    }

    private static PatchGenerator parse(String[] args) throws Exception {

        File patchConfig = null;
        File oldFile = null;
        File newFile = null;
        File patchFile = null;
        boolean includeVersion = false;
        File combineWith = null;
        boolean skipLayersNoConfig = false;
        boolean skipMiscFiles = false;
        List<String> includedMiscFiles = null;

        final int argsLength = args.length;
        for (int i = 0; i < argsLength; i++) {
            final String arg = args[i];
            try {
                if ("--version".equals(arg) || "-v".equals(arg)
                        || "-version".equals(arg) || "-V".equals(arg)) {
                    final String homeDir = getSecurityManager() == null ? getProperty("jboss.home.dir") : Usage.getSystemProperty("jboss.home.dir");
                    ProductConfig productConfig = new ProductConfig(Module.getBootModuleLoader(), homeDir, Collections.emptyMap());
                    System.out.println(productConfig.getPrettyVersionString());
                    return null;
                } else if ("--help".equals(arg) || "-h".equals(arg) || "-H".equals(arg)) {
                    usage();
                    return null;
                } else if (arg.startsWith(APPLIES_TO_DIST)) {
                    String val = arg.substring(APPLIES_TO_DIST.length() + 1);
                    oldFile = new File(val);
                    if (!oldFile.exists()) {
                        System.err.printf(PatchLogger.ROOT_LOGGER.fileDoesNotExist(arg));
                        usage();
                        return null;
                    } else if (!oldFile.isDirectory()) {
                        System.err.printf(PatchGenLogger.fileIsNotADirectory(arg));
                        usage();
                        return null;
                    }
                } else if (arg.startsWith(UPDATED_DIST)) {
                    String val = arg.substring(UPDATED_DIST.length() + 1);
                    newFile = new File(val);
                    if (!newFile.exists()) {
                        System.err.printf(PatchLogger.ROOT_LOGGER.fileDoesNotExist(arg));
                        usage();
                        return null;
                    } else if (!newFile.isDirectory()) {
                        System.err.printf(PatchGenLogger.fileIsNotADirectory(arg));
                        usage();
                        return null;
                    }
                } else if (arg.startsWith(PATCH_CONFIG)) {
                    String val = arg.substring(PATCH_CONFIG.length() + 1);
                    patchConfig = new File(val);
                    if (!patchConfig.exists()) {
                        System.err.printf(PatchLogger.ROOT_LOGGER.fileDoesNotExist(arg));
                        usage();
                        return null;
                    } else if (patchConfig.isDirectory()) {
                        System.err.printf(PatchGenLogger.fileIsADirectory(arg));
                        usage();
                        return null;
                    }
                } else if (arg.startsWith(OUTPUT_FILE)) {
                    String val = arg.substring(OUTPUT_FILE.length() + 1);
                    patchFile = new File(val);
                    if (patchFile.exists() && patchFile.isDirectory()) {
                        System.err.printf(PatchGenLogger.fileIsADirectory(arg));
                        usage();
                        return null;
                    }
                } else if (arg.equals(DETAILED_INSPECTION)) {
                    ModuleDiffUtils.deepInspection = true;
                } else if (arg.equals(INCLUDE_VERSION)) {
                    includeVersion = true;
                } else if (arg.equals(CREATE_TEMPLATE)) {
                    TemplateGenerator.generate(args);
                    return null;
                } else if (arg.equals(ASSEMBLE_PATCH_BUNDLE)) {
                    PatchBundleGenerator.assemble(args);
                    return null;
                } else if (arg.startsWith(COMBINE_WITH)) {
                    String val = arg.substring(COMBINE_WITH.length() + 1);
                    combineWith = new File(val);
                    if (!combineWith.exists()) {
                        System.err.printf(PatchLogger.ROOT_LOGGER.fileDoesNotExist(arg));
                        usage();
                        return null;
                    }
                } else if (arg.startsWith(SKIP_LAYERS_NO_CONFIG)) {
                    skipLayersNoConfig = true;
                } else if (arg.equals(SKIP_MISC_FILES)) {
                    skipMiscFiles = true;
                } else if (arg.startsWith(INCLUDED_MISC_FILES)) {
                    String val = arg.substring(SKIP_MISC_FILES.length() + 1);
                    includedMiscFiles = new ArrayList<>(Arrays.asList(val.split(",")));
                }
            } catch (IndexOutOfBoundsException e) {
                System.err.printf(PatchGenLogger.argumentExpected(arg));
                usage();
                return null;
            }
        }

        if (patchConfig == null) {
            System.err.printf(PatchGenLogger.missingRequiredArgs(Collections.singleton(PATCH_CONFIG)));
            usage();
            return null;
        }

        if (includedMiscFiles != null && !skipMiscFiles) {
            System.err.println(PatchGenLogger.includedMiscFilesAndNotSkippingMiscFiles());
            usage();
            return null;
        }

        SkipMiscFilesContentItemFilter skipMiscFilesContentItemFilter = null;
        if (skipMiscFiles) {
            skipMiscFilesContentItemFilter = SkipMiscFilesContentItemFilter.create(includedMiscFiles);
        }

        return new PatchGenerator(patchConfig, oldFile, newFile, patchFile, includeVersion, combineWith, skipLayersNoConfig, skipMiscFilesContentItemFilter);
    }

    private static void usage() {

        Usage usage = new Usage();

        usage.addArguments(APPLIES_TO_DIST + "=<file>");
        usage.addInstruction("Filesystem path of a pristine unzip of the distribution of the version of the software to which the generated patch applies");

        usage.addArguments("-h", "--help");
        usage.addInstruction("Display this message and exit");

        usage.addArguments(OUTPUT_FILE + "=<file>");
        usage.addInstruction("Filesystem location to which the generated patch file should be written");

        usage.addArguments(PATCH_CONFIG + "=<file>");
        usage.addInstruction("Filesystem path of the patch generation configuration file to use");

        usage.addArguments(UPDATED_DIST + "=<file>");
        usage.addInstruction("Filesystem path of a pristine unzip of a distribution of software which contains the changes that should be incorporated in the patch");

        usage.addArguments("-v", "--version");
        usage.addInstruction("Print version and exit");

        usage.addArguments(DETAILED_INSPECTION);
        usage.addInstruction("Enable detailed inspection for all modules.");

        usage.addArguments(COMBINE_WITH + "=<file>");
        usage.addInstruction("Filesystem path of the previous CP to be included into the same package with the newly generated one");

        usage.addArguments(SKIP_LAYERS_NO_CONFIG);
        usage.addInstruction("Does not perform comparison of layers which are not set up in the patch config xml");

        usage.addArguments(SKIP_MISC_FILES);
        usage.addInstruction("If set, misc files are not included in the resulting patch. Still individual files can be included with the " + INCLUDED_MISC_FILES + " argument");

        usage.addArguments(INCLUDED_MISC_FILES + "=<file list>");
        usage.addInstruction("Comma-separated list of relative file paths included in the resulting patch. Only allowed if " + SKIP_MISC_FILES + " has been set");

        String headline = usage.getDefaultUsageHeadline("patch-gen");
        System.out.print(usage.usage(headline));

    }

    static RuntimeException processingError(String message, Object... arguments) {
        return new RuntimeException(String.format(message, arguments)); // no 18n for the generation
    }

    static RuntimeException processingError(Exception e, String message, Object... arguments) {
        return new RuntimeException(String.format(message, arguments), e); // no 18n for the generation
    }

}
