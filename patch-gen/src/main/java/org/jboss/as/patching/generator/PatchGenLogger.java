/*
 * Copyright 2018 Red Hat, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jboss.as.patching.generator;

import static org.jboss.as.patching.generator.PatchGenerator.INCLUDED_MISC_FILES;
import static org.jboss.as.patching.generator.PatchGenerator.SKIP_MISC_FILES;

import java.io.PrintStream;
import java.util.Set;

import org.jboss.as.controller.OperationFailedException;

/**
 *
 * @author Alexey Loubyansky
 */
public class PatchGenLogger {

    public static String argumentExpected(String arg) {
        return "Argument expected for option " + arg;
    }

    public static String missingRequiredArgs(Set<String> set) {
        return "Missing required argument(s): " + set;
    }

    public static String fileIsNotADirectory(String arg) {
        return "File at path specified by argument " + arg + " is not a directory";
    }

    public static String fileIsADirectory(String arg) {
        return "File at path specified by argument " + arg + " is a directory";
    }

    public static OperationFailedException patchActive(String patchId) {
        return new OperationFailedException("Cannot complete operation. Patch '" + patchId + "' is currently active");
    }

    public static String includedMiscFilesAndNotSkippingMiscFiles() {
        return INCLUDED_MISC_FILES + " was used, but it can only be used if " + SKIP_MISC_FILES + " is also specified.";
    }
}
