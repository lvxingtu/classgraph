/*
 * This file is part of ClassGraph.
 *
 * Author: Luke Hutchison
 *
 * Hosted at: https://github.com/lukehutch/fast-classpath-scanner
 *
 * --
 *
 * The MIT License (MIT)
 *
 * Copyright (c) 2018 Luke Hutchison
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated
 * documentation files (the "Software"), to deal in the Software without restriction, including without
 * limitation the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of
 * the Software, and to permit persons to whom the Software is furnished to do so, subject to the following
 * conditions:
 *
 * The above copyright notice and this permission notice shall be included in all copies or substantial
 * portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT
 * LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO
 * EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN
 * AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE
 * OR OTHER DEALINGS IN THE SOFTWARE.
 */
package io.github.classgraph;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import io.github.classgraph.ScanSpec.ScanSpecPathMatch;
import io.github.classgraph.utils.ClasspathOrModulePathEntry;
import io.github.classgraph.utils.FileUtils;
import io.github.classgraph.utils.InputStreamOrByteBufferAdapter;
import io.github.classgraph.utils.LogNode;
import io.github.classgraph.utils.NestedJarHandler;
import io.github.classgraph.utils.Recycler;
import io.github.classgraph.utils.URLPathEncoder;

/** A module classpath element. */
class ClasspathElementModule extends ClasspathElement {
    private final ModuleRef moduleRef;

    private Recycler<ModuleReaderProxy, IOException> moduleReaderProxyRecycler;

    /** A zip/jarfile classpath element. */
    ClasspathElementModule(final ClasspathOrModulePathEntry classpathEltPath, final ScanSpec scanSpec,
            final boolean scanFiles, final NestedJarHandler nestedJarHandler, final LogNode log) {
        super(classpathEltPath, scanSpec, scanFiles);
        moduleRef = classpathEltPath.getModuleRef();
        if (moduleRef == null) {
            // Should not happen
            throw new IllegalArgumentException();
        }
        try {
            moduleReaderProxyRecycler = nestedJarHandler.getModuleReaderProxyRecycler(moduleRef, log);
        } catch (final Exception e) {
            if (log != null) {
                log.log("Exception while creating zipfile recycler for " + moduleRef.getName() + " : " + e);
            }
            skipClasspathElement = true;
            return;
        }
        if (scanFiles) {
            fileMatches = new ArrayList<>();
            classfileMatches = new ArrayList<>();
            fileToLastModified = new HashMap<>();
        }
    }

    private Resource newClasspathResource(final String moduleResourcePath) {
        return new Resource() {
            private Recycler<ModuleReaderProxy, IOException>.Recyclable moduleReaderProxyRecyclable;
            private ModuleReaderProxy moduleReaderProxy;

            @Override
            public String getPath() {
                return moduleResourcePath;
            }

            @Override
            public String getPathRelativeToClasspathElement() {
                return moduleResourcePath;
            }

            @Override
            public URL getURL() {
                try {
                    if (moduleRef.getLocationStr() == null) {
                        // If there is no known module location, just guess a "jrt:/" path based on the module
                        // name, so that the user can see something reasonable in the result
                        return new URL(new URL("jrt:/" + moduleRef.getName()).toString() + "!"
                                + URLPathEncoder.encodePath(moduleResourcePath));
                    } else {
                        return new URL(new URL(moduleRef.getLocationStr()).toString() + "!"
                                + URLPathEncoder.encodePath(moduleResourcePath));
                    }
                } catch (final MalformedURLException e) {
                    throw new IllegalArgumentException("Could not form URL for module location: "
                            + moduleRef.getLocationStr() + " ; path: " + moduleResourcePath);
                }
            }

            @Override
            public ByteBuffer read() throws IOException {
                if (skipClasspathElement) {
                    // Shouldn't happen
                    throw new IOException("Module could not be opened");
                }
                if (byteBuffer != null || inputStream != null || moduleReaderProxy != null) {
                    throw new IllegalArgumentException(
                            "Resource is already open -- cannot open it again without first calling close()");
                } else {
                    try {
                        moduleReaderProxyRecyclable = moduleReaderProxyRecycler.acquire();
                        moduleReaderProxy = moduleReaderProxyRecyclable.get();
                        // ModuleReader#read(String name) internally calls:
                        // ByteBuffer.wrap(open(name).readAllBytes())
                        byteBuffer = moduleReaderProxy.read(moduleResourcePath);
                        length = byteBuffer.remaining();
                        return byteBuffer;

                    } catch (final Exception e) {
                        close();
                        throw new IOException("Could not open " + this, e);
                    }
                }
            }

            @Override
            InputStreamOrByteBufferAdapter openOrRead() throws IOException {
                return InputStreamOrByteBufferAdapter.create(open());
            }

            @Override
            public InputStream open() throws IOException {
                if (skipClasspathElement) {
                    // Shouldn't happen
                    throw new IOException("Module could not be opened");
                }
                if (byteBuffer != null || inputStream != null || moduleReaderProxy != null) {
                    throw new IllegalArgumentException(
                            "Resource is already open -- cannot open it again without first calling close()");
                } else {
                    try {
                        moduleReaderProxyRecyclable = moduleReaderProxyRecycler.acquire();
                        moduleReaderProxy = moduleReaderProxyRecyclable.get();
                        inputStream = moduleReaderProxy.open(moduleResourcePath);
                        return inputStream;

                    } catch (final Exception e) {
                        close();
                        throw new IOException("Could not open " + this, e);
                    }
                }
            }

            @Override
            public byte[] load() throws IOException {
                try {
                    read();
                    final byte[] byteArray = byteBufferToByteArray();
                    length = byteArray.length;
                    return byteArray;
                } finally {
                    close();
                }
            }

            @Override
            public void close() {
                if (inputStream != null) {
                    try {
                        inputStream.close();
                        inputStream = null;
                    } catch (final IOException e) {
                        // Ignore
                    }
                }
                if (byteBuffer != null) {
                    if (moduleReaderProxy != null) {
                        try {
                            moduleReaderProxy.release(byteBuffer);
                        } catch (final Exception e) {
                            // Ignore
                        }
                    }
                    byteBuffer = null;
                }
                if (moduleReaderProxy != null) {
                    // Release any open ByteBuffer
                    // Don't call ModuleReaderProxy#close(), leave the ModuleReaderProxy open in the recycler.
                    // Just set the ref to null here. The ModuleReaderProxy will be closed by
                    // ClasspathElementModule#close().
                    moduleReaderProxy = null;
                }
                if (moduleReaderProxyRecyclable != null) {
                    // Recycle the (open) ModuleReaderProxy instance.
                    moduleReaderProxyRecyclable.close();
                    moduleReaderProxyRecyclable = null;
                }
            }

            @Override
            protected String toStringImpl() {
                return "[module " + moduleRef.getName() + "]/" + moduleResourcePath;
            }
        };
    }

    /** Scan for package matches within module */
    @Override
    void scanPaths(final LogNode log) {
        final String moduleLocationStr = moduleRef.getLocationStr();
        final LogNode subLog = log == null ? null
                : log.log(moduleLocationStr, "Scanning module classpath entry " + classpathEltPath);
        try (Recycler<ModuleReaderProxy, IOException>.Recyclable moduleReaderProxyRecyclable = //
                moduleReaderProxyRecycler.acquire()) {
            final ModuleReaderProxy moduleReaderProxy = moduleReaderProxyRecyclable.get();

            // Look for whitelisted files in the module.
            List<String> resourceRelativePaths;
            try {
                resourceRelativePaths = moduleReaderProxy.list();
            } catch (final Exception e) {
                if (subLog != null) {
                    subLog.log("Could not get resource list for module " + moduleRef.getName(), e);
                }
                return;
            }
            String prevParentRelativePath = null;
            ScanSpecPathMatch prevParentMatchStatus = null;
            for (final String relativePath : resourceRelativePaths) {
                // From ModuleReader#find(): "If the module reader can determine that the name locates a
                // directory then the resulting URI will end with a slash ('/')."  But from the documentation
                // for ModuleReader#list(): "Whether the stream of elements includes names corresponding to
                // directories in the module is module reader specific."  We don't have a way of checking if
                // a resource is a directory without trying to open it, unless ModuleReader#list() also decides
                // to put a "/" on the end of resource paths corresponding to directories. Skip directories if
                // they are found, but if they are not able to be skipped, we will have to settle for having
                // some IOExceptions thrown when directories are mistaken for files when trying to call a
                // FileMatchProcessor on a directory path that matches a given path criterion.
                if (relativePath.endsWith("/")) {
                    continue;
                }

                // Get match status of the parent directory of this resource's relative path (or reuse the last
                // match status for speed, if the directory name hasn't changed).
                final int lastSlashIdx = relativePath.lastIndexOf("/");
                final String parentRelativePath = lastSlashIdx < 0 ? "/"
                        : relativePath.substring(0, lastSlashIdx + 1);
                final boolean parentRelativePathChanged = !parentRelativePath.equals(prevParentRelativePath);
                final ScanSpecPathMatch parentMatchStatus = //
                        prevParentRelativePath == null || parentRelativePathChanged
                                ? scanSpec.dirWhitelistMatchStatus(parentRelativePath)
                                : prevParentMatchStatus;
                prevParentRelativePath = parentRelativePath;
                prevParentMatchStatus = parentMatchStatus;

                // Class can only be scanned if it's within a whitelisted path subtree, or if it is a classfile
                // that has been specifically-whitelisted
                if (parentMatchStatus != ScanSpecPathMatch.HAS_WHITELISTED_PATH_PREFIX
                        && parentMatchStatus != ScanSpecPathMatch.AT_WHITELISTED_PATH
                        && (parentMatchStatus != ScanSpecPathMatch.AT_WHITELISTED_CLASS_PACKAGE
                                || !scanSpec.isSpecificallyWhitelistedClass(relativePath))) {
                    continue;
                }

                if (subLog != null) {
                    subLog.log(relativePath, "Found whitelisted file: " + relativePath);
                }

                if (scanSpec.enableClassInfo) {
                    // Store relative paths of any classfiles encountered
                    if (FileUtils.isClassfile(relativePath)) {
                        classfileMatches.add(newClasspathResource(relativePath));
                    }
                }

                // Record all classpath resources found in whitelisted paths
                fileMatches.add(newClasspathResource(relativePath));

                // Last modified time is not tracked for system modules, since they have a "jrt:/" URL
                if (!moduleRef.isSystemModule()) {
                    final File moduleFile = moduleRef.getLocationFile();
                    if (moduleFile.exists()) {
                        fileToLastModified.put(moduleFile, moduleFile.lastModified());
                    }
                }
            }
        } catch (final IOException e) {
            if (subLog != null) {
                subLog.log("Exception opening module " + classpathEltPath, e);
            }
            skipClasspathElement = true;
            return;
        }
        if (subLog != null) {
            subLog.addElapsedTime();
        }
    }

    /** Close and free all open ZipFiles. */
    @Override
    void close() {
        if (moduleReaderProxyRecycler != null) {
            // Close all ModuleReaderProxy instances, which will in turn call ModuleReader#close()
            // on each open ModuleReader.
            moduleReaderProxyRecycler.close();
        }
    }
}
